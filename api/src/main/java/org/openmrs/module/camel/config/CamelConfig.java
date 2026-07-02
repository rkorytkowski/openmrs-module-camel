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

import io.hawt.embedded.Main;
import org.apache.camel.CamelContext;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.es.ElasticsearchComponent;
import org.apache.camel.component.jms.JmsComponent;
import org.apache.camel.spring.SpringCamelContext;
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
import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;

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
	
	@Bean("hawtio")
	public Main hawtio(@Value("${camel.hawtio.enabled:false}") boolean hawtioEnabled,
	        @Value("${camel.hawtio.jaas.overwrite:true}") boolean jaasOverwrite,
	        @Value("${camel.hawtio.username:admin}") String username, @Value("${camel.hawtio.password:}") String password,
	        @Value("${camel.hawtio.port:10001}") int port, @Value("${camel.hawtio.host:127.0.0.1}") String host)
	        throws Exception {
		
		if (!hawtioEnabled) {
			return null;
		}
		
		// Enable authentication for embedded monitoring
		System.setProperty("hawtio.authenticationEnabled", "true");
		System.setProperty("hawtio.realm", "hawtio");
		System.setProperty("hawtio.roles", "admin");
		
		if (jaasOverwrite) {
			if (password == null || password.isEmpty()) {
				throw new IllegalArgumentException("camel.hawtio.password property cannot be null or empty");
			}
			
			System.setProperty("camel.hawtio.username", username);
			System.setProperty("camel.hawtio.password", password);
			
			URL loginConfUrl = getClass().getResource("/hawtio-login.conf");
			System.setProperty("java.security.auth.login.config", loginConfUrl.toString());
		}
		
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
		
		Main main = new Main();
		main.setWar(consoleWar.getAbsolutePath());
		main.setContextPath("/hawtio");
		main.setPort(port); // Expose on a dedicated port to avoid conflicting with Tomcat/OpenMRS
		main.run(false); // Run in non-blocking mode so OpenMRS continues to start up
		// The embedded hawtio does not implement any stop logic. It will be only stopped when JVM is stopped.
		return main;
	}
}
