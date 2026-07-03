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
import org.eclipse.jetty.webapp.WebAppContext;
import org.elasticsearch.client.RestClient;
import org.hibernate.SessionFactory;
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
		// Extract the low-level Elasticsearch RestClient from the Hibernate Search backend safely
		try {
			RestClient restClient = Search.mapping(sessionFactory).backend().unwrap(RestClient.class);
			
			ElasticsearchComponent elasticsearchComponent = new ElasticsearchComponent();
			elasticsearchComponent.setClient(restClient);
			return elasticsearchComponent;
		}
		catch (Exception e) {
			// Backend is not Elasticsearch (e.g., Lucene) or not initialized
			return null;
		}
	}
	
	@Bean(name = "hawtio", destroyMethod = "stop")
	public Server hawtio(@Value("${camel.hawtio.enabled:false}") boolean hawtioEnabled,
	        @Value("${camel.hawtio.username:admin}") String username, @Value("${camel.hawtio.password:}") String password,
	        @Value("${camel.hawtio.port:10001}") int port, @Value("${camel.hawtio.host:127.0.0.1}") String host)
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
		
		// Install a delegating JAAS Configuration that handles only the "hawtio" realm
		// and delegates every other realm to whatever was configured before. This avoids
		// writing credentials to JVM system properties and does not overwrite existing
		// JAAS configurations (e.g. from openmrs-module-artemis).
		javax.security.auth.login.Configuration existing;
		try {
			existing = javax.security.auth.login.Configuration.getConfiguration();
		}
		catch (Exception e) {
			existing = null;
		}
		final javax.security.auth.login.Configuration delegate = existing;
		final Map<String, Object> loginOptions = new HashMap<>();
		loginOptions.put("username", username);
		loginOptions.put("password", password);
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
		if (!consoleWar.exists()) {
			try (InputStream is = getClass().getResourceAsStream("/hawtio.war")) {
				if (is != null) {
					Files.copy(is, consoleWar.toPath(), StandardCopyOption.REPLACE_EXISTING);
				} else {
					log.warn(
					    "hawtio.war not found in classpath. Hawtio Web Console may fail to start or return a 404 error.");
				}
			}
		}
		
		// Build the Jetty Server directly so we hold the reference and can stop it
		// on context close, preventing BindException when the Spring context refreshes.
		Server server = new Server(new InetSocketAddress(InetAddress.getByName(host), port));
		WebAppContext webapp = new WebAppContext();
		webapp.setContextPath("/hawtio");
		webapp.setWar(consoleWar.getAbsolutePath());
		webapp.setParentLoaderPriority(true);
		File tempDir = new File(dataDir + File.separator + "hawtio-tmp");
		tempDir.mkdirs();
		webapp.setTempDirectory(tempDir);
		server.setHandler(webapp);
		server.start();
		return server;
	}
}
