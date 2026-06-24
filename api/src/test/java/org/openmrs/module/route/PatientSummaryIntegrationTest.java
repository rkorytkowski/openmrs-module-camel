/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.route;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.GetResponse;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.apache.camel.CamelContext;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.es.ElasticsearchComponent;
import org.apache.camel.component.jms.JmsComponent;
import org.apache.http.HttpHost;
import org.awaitility.Awaitility;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Person;
import org.openmrs.api.PatientService;
import org.openmrs.event.CDCEvent;
import org.openmrs.event.EventPublisher;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;

@Testcontainers
public class PatientSummaryIntegrationTest extends BaseModuleContextSensitiveTest {
	
	// 1. Define the Artemis Docker Container so we don't rely in tests on e.g. openmrs-module-artemis.
	@Container
	public static GenericContainer<?> artemis = new GenericContainer<>("apache/activemq-artemis:latest-alpine")
	        .withEnv("ARTEMIS_USER", "admin").withEnv("ARTEMIS_PASSWORD", "admin").withExposedPorts(61616);
	
	// 2. Define the Elasticsearch Docker Container so we don't rely on Hibernate Search to be configured to connect to ES
	@Container
	public static ElasticsearchContainer elasticsearch = new ElasticsearchContainer(
	        "docker.elastic.co/elasticsearch/elasticsearch:7.17.13").withEnv("discovery.type", "single-node")
	        .withEnv("xpack.security.enabled", "false"); // Disable auth for local testing
	
	@Autowired
	private CamelContext camelContext;
	
	@Autowired
	private ProducerTemplate producerTemplate;
	
	@Autowired
	private List<RouteBuilder> routes;
	
	private ElasticsearchClient esClient;
	
	@Autowired
	private PatientService patientService;
	
	@Autowired
	private EventPublisher eventPublisher;
	
	@Autowired
	private PatientChangesTestListener patientChangesTestListener;
	
	@BeforeEach
	public void setupTestcontainersConnections() throws Exception {
		JmsComponent jmsComponent = camelContext.getComponent("jms", JmsComponent.class);
		if (jmsComponent == null) {
			jmsComponent = new JmsComponent();
			camelContext.addComponent("jms", jmsComponent);
		}
		// Reconfigure the JMS component to point to the dynamic Testcontainers Artemis port
		String brokerUrl = "tcp://" + artemis.getHost() + ":" + artemis.getMappedPort(61616);
		ActiveMQConnectionFactory cf = new ActiveMQConnectionFactory(brokerUrl, "admin", "admin");
		jmsComponent.setConnectionFactory(cf);
		
		ElasticsearchComponent esComponent = camelContext.getComponent("elasticsearch", ElasticsearchComponent.class);
		if (esComponent == null) {
			esComponent = new ElasticsearchComponent();
			camelContext.addComponent("elasticsearch", esComponent);
		}
		
		RestClient restClient = RestClient.builder(HttpHost.create(elasticsearch.getHttpHostAddress())).build();
		RestClientTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
		esClient = new ElasticsearchClient(transport);
		esComponent.setClient(restClient);
		
		// Now that the components have valid Testcontainers connections, 
		// it is safe to add the routes to the Camel Context.
		for (RouteBuilder route : routes) {
			camelContext.addRoutes(route);
		}
		
		if (!camelContext.isStarted()) {
			camelContext.start();
		}
	}
	
	@AfterEach
	public void teardown() {
		// Gracefully shut down Camel routes BEFORE the Spring Context and DB are destroyed
		if (camelContext != null && camelContext.isStarted()) {
			camelContext.stop();
		}
	}

	@Test
	public void testRoute() {
		// 1. Publish messages with personId to jms
		producerTemplate.sendBodyAndHeader("jms:queue:patientChanges", "2", "personId", "2");
		producerTemplate.sendBodyAndHeader("jms:queue:patientChanges", "2", "personId", "2");
		producerTemplate.sendBodyAndHeader("jms:queue:patientChanges", "2", "personId", "2");
		
		String patientUuid = patientService.getPatient(2).getUuid();
		// 2. Give Camel a moment to asynchronously route from Artemis -> Elasticsearch
		// and query the real Elasticsearch container to ensure the document was successfully indexed
		Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> {
			try {
				GetResponse<Object> response = esClient.get(g -> g.index("patient-summary-index").id(patientUuid),
				    Object.class);
				return response.found();
			}
			catch (Exception e) {
				// Elasticsearch might throw an exception if the index doesn't exist yet
				return false;
			}
		});
	}
	
	@Test
	public void testOnCdcEvent() {
		patientChangesTestListener.getEvents().clear();
		
		// Create a mock CDCEvent for a Person change
		CDCEvent<Person> event = new CDCEvent<>(Person.class);
		event.setOperation(CDCEvent.Operation.UPDATE);
		Map<String, Object> pk = new HashMap<>();
		pk.put("person_id", 7);
		event.setPrimaryKey(pk);
		
		eventPublisher.publishEvent(event);
		eventPublisher.publishEvent(event);
		eventPublisher.publishEvent(event);
		
		assertThat(patientChangesTestListener.getEvents(), hasSize(3));
	}
	
}
