/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.camel.config;

import org.apache.camel.CamelContext;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.es.ElasticsearchComponent;
import org.apache.camel.component.jms.JmsComponent;
import org.apache.camel.spring.SpringCamelContext;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.ee10.webapp.WebAppClassLoader;
import org.eclipse.jetty.ee10.webapp.WebAppContext;
import org.elasticsearch.client.RestClient;
import org.hibernate.SessionFactory;
import org.hibernate.search.backend.elasticsearch.ElasticsearchBackend;
import org.hibernate.search.mapper.orm.Search;
import org.openmrs.util.OpenmrsUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.jms.ConnectionFactory;
import javax.security.auth.login.AppConfigurationEntry;
import java.io.File;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Configuration
public class CamelConfig {
	
	private static final Logger log = LoggerFactory.getLogger(CamelConfig.class);
	
	@Bean
	public CamelContext camelContext(ApplicationContext applicationContext, List<RouteBuilder> routes,
	        @Value("${camel.autoDiscoverRoutes:true}") boolean autoDiscoverRoutes) throws Exception {
		SpringCamelContext camelContext = new SpringCamelContext(applicationContext);
		if (autoDiscoverRoutes) {
			for (RouteBuilder route : routes) {
				camelContext.addRoutes(route);
			}
		}
		return camelContext;
	}
	
	@Bean
	public ProducerTemplate producerTemplate(CamelContext camelContext) {
		return camelContext.createProducerTemplate();
	}
	
	@Bean("jms")
	public JmsComponent jms(@Autowired(required = false) ConnectionFactory connectionFactory) {
		if (connectionFactory == null) {
			return null;
		}
		
		JmsComponent jmsComponent = new JmsComponent();
		jmsComponent.setConnectionFactory(connectionFactory);
		return jmsComponent;
	}
	
	@Bean("elasticsearch")
	public ElasticsearchComponent elasticsearch(SessionFactory sessionFactory) {
		try {
			ElasticsearchBackend esBackend = Search.mapping(sessionFactory).backend().unwrap(ElasticsearchBackend.class);
			RestClient restClient = esBackend.client(RestClient.class);
			ElasticsearchComponent elasticsearchComponent = new ElasticsearchComponent();
			elasticsearchComponent.setClient(restClient);
			return elasticsearchComponent;
		}
		catch (Exception e) {
			log.warn("Elasticsearch backend not available, camel-elasticsearch component disabled: {}", e.getMessage());
			return null;
		}
	}
	
	@Bean(name = "hawtio", destroyMethod = "stop")
	public HawtioHandle hawtio(@Value("${camel.hawtio.enabled:false}") boolean hawtioEnabled,
	        @Value("${camel.hawtio.username:admin}") String username, @Value("${camel.hawtio.password:}") String password,
	        @Value("${camel.hawtio.port:8181}") int port, @Value("${camel.hawtio.host:127.0.0.1}") String host)
	        throws Exception {
		
		if (!hawtioEnabled) {
			return null;
		}
		
		if (password == null || password.isEmpty()) {
			throw new IllegalArgumentException("camel.hawtio.password must be set when hawtio is enabled");
		}
		
		// Enable authentication for embedded monitoring
		System.setProperty("hawtio.authenticationEnabled", "true");
		System.setProperty("hawtio.realm", "hawtio");
		System.setProperty("hawtio.roles", "admin");
		
		// Capture the existing Configuration so we can restore it on stop. Without restoring,
		// each context refresh (triggered by any module install/start/stop) installs a new
		// wrapper that keeps the previous one as its delegate, forming a chain that pins every
		// prior classloader generation and causes Metaspace OOM.
		javax.security.auth.login.Configuration previousConfig;
		try {
			previousConfig = javax.security.auth.login.Configuration.getConfiguration();
		}
		catch (Exception e) {
			previousConfig = null;
		}
		final Map<String, Object> loginOptions = new HashMap<>();
		loginOptions.put("username", username);
		loginOptions.put("password", password);
		final javax.security.auth.login.Configuration delegate = previousConfig;
		javax.security.auth.login.Configuration.setConfiguration(new javax.security.auth.login.Configuration() {
			
			@Override
			public AppConfigurationEntry[] getAppConfigurationEntry(String name) {
				if ("hawtio".equals(name)) {
					return new AppConfigurationEntry[] { new AppConfigurationEntry(CamelLoginModule.class.getName(),
					        AppConfigurationEntry.LoginModuleControlFlag.REQUIRED, loginOptions) };
				}
				return delegate != null ? delegate.getAppConfigurationEntry(name) : null;
			}
		});
		
		String dataDir = OpenmrsUtil.getApplicationDataDirectory() + File.separator + "camel";
		File dataDirFile = new File(dataDir);
		if (!dataDirFile.exists()) {
			dataDirFile.mkdirs();
		}
		
		File webDirFile = new File(dataDirFile, "hawtio");
		if (!webDirFile.exists()) {
			webDirFile.mkdirs();
		}
		
		File consoleWar = new File(webDirFile, "hawtio.war");
		try (InputStream is = getClass().getResourceAsStream("/hawtio.war")) {
			if (is != null) {
				Files.copy(is, consoleWar.toPath(), StandardCopyOption.REPLACE_EXISTING);
			} else {
				log.warn("hawtio.war not found in classpath. Hawtio Web Console may fail to start or return a 404 error.");
			}
		}
		
		// Build the Jetty Server directly so we hold the reference and can stop it
		// on context close, preventing BindException when the Spring context refreshes.
		Server server = new Server(new InetSocketAddress(InetAddress.getByName(host), port));
		WebAppContext webapp = new WebAppContext();
		webapp.setContextPath("/hawtio");
		webapp.setWar(consoleWar.getAbsolutePath());
		// Pin the WebAppClassLoader's parent to this module's own classloader. Without this,
		// Jetty defaults to Thread.currentThread().getContextClassLoader() which at OpenMRS
		// startup is the OpenmrsClassLoader (a meta-loader that sees all modules). If another
		// module (e.g. openmrs-module-artemis) also bundles Jetty, the OpenmrsClassLoader may
		// resolve jakarta.servlet.Filter from that module before reaching Tomcat, while our
		// FilterHolder resolves it through the camel module chain — two different class objects,
		// causing FilterHolder.doStart to throw "is not a jakarta.servlet.Filter".
		webapp.setClassLoader(new IsolatedWebAppClassLoader(CamelConfig.class.getClassLoader(), webapp));
		File tempDir = new File(dataDir + File.separator + "hawtio-tmp");
		tempDir.mkdirs();
		webapp.setTempDirectory(tempDir);
		server.setHandler(webapp);
		server.start();
		return new HawtioHandle(server, previousConfig);
	}
	
	/**
	 * WebAppClassLoader that prevents parent-classloader Log4j2Plugins.dat files from leaking into the
	 * hawtio WAR's log4j2 plugin discovery. Without this, log4j2 inside the WAR finds
	 * OpenmrsPropertyLookup (and standard log4j2 lookups from Artemis's bundled log4j-core) through the
	 * parent classloader chain and throws ClassCastException because those classes implement StrLookup
	 * from a different log4j2 JAR than what hawtio bundles.
	 */
	private static class IsolatedWebAppClassLoader extends WebAppClassLoader {
		
		IsolatedWebAppClassLoader(ClassLoader parent, WebAppContext context) throws java.io.IOException {
			super(parent, context);
		}
		
		@Override
		public java.util.Enumeration<java.net.URL> getResources(String name) throws java.io.IOException {
			if ("META-INF/org/apache/logging/log4j/core/config/plugins/Log4j2Plugins.dat".equals(name)) {
				return findResources(name);
			}
			return super.getResources(name);
		}
	}
	
	/**
	 * Holds the Jetty server and the JAAS Configuration that was in place before hawtio was started, so
	 * both can be restored atomically when the Spring context closes.
	 */
	public static class HawtioHandle {
		
		private final Server server;
		
		private final javax.security.auth.login.Configuration previousConfig;
		
		HawtioHandle(Server server, javax.security.auth.login.Configuration previousConfig) {
			this.server = server;
			this.previousConfig = previousConfig;
		}
		
		public void stop() throws Exception {
			server.stop();
			javax.security.auth.login.Configuration.setConfiguration(previousConfig);
		}
	}
}
