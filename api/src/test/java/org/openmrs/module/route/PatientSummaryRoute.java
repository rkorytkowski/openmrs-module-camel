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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.openmrs.Patient;
import org.openmrs.Person;
import org.openmrs.PersonAddress;
import org.openmrs.PersonName;
import org.openmrs.PatientIdentifier;
import org.openmrs.PersonAttribute;
import org.openmrs.api.PatientService;
import org.openmrs.api.context.Context;
import org.openmrs.event.CDCEvent;
import org.openmrs.event.EventPublisher;
import org.openmrs.event.broker.BrokerOutgoingEvent;
import org.openmrs.util.PrivilegeConstants;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class PatientSummaryRoute extends RouteBuilder {
	
	private final List<Class<?>> observedTypes = Arrays.asList(Patient.class, Person.class, PersonName.class,
	    PersonAddress.class, PatientIdentifier.class, PersonAttribute.class);
	
	private final EventPublisher eventPublisher;
	
	private final PatientService patientService;
	
	private final ObjectMapper esObjectMapper;
	
	public PatientSummaryRoute(EventPublisher eventPublisher, PatientService patientService) {
		this.eventPublisher = eventPublisher;
		this.patientService = patientService;
		
		// Create a clean mapper for Elasticsearch to prevent default typing (e.g. @class, java.util.HashMap) 
		// from polluting the JSON document.
		this.esObjectMapper = new ObjectMapper();
		this.esObjectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
		this.esObjectMapper.setDateFormat(new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ"));
	}
	
	/**
	 * Listen to CDCEvents related to patient summary and publish them to a broker for asynchronous
	 * processing.
	 * <p>
	 * We chose to create a native Spring listener instead of using Camel's spring-event component so
	 * that we register a listener only for CDCEvents instead of having a global application listener
	 * for all events in the system that Camel creates. It's more efficient in terms of CPU and memory
	 * usage as Camel creates Exchange and Message objects for each event. In addition, we do not rely
	 * on a single consumer, which under heavy load may delay the processing of CDCEvents.
	 *
	 * @param event
	 */
	@EventListener
	public void onCdcEvent(CDCEvent<?> event) {
		if (observedTypes.contains(event.getEntityType()) && !CDCEvent.Operation.READ.equals(event.getOperation())) {
			String personId;
			Map<String, Object> newState = event.getNewState();
			
			if (Patient.class.isAssignableFrom(event.getEntityType())) {
				// Patient table uses patient_id as its primary key column, not person_id
				personId = String.valueOf(event.getPrimaryKey().get("patient_id"));
			} else if (Person.class.isAssignableFrom(event.getEntityType())) {
				personId = String.valueOf(event.getPrimaryKey().get("person_id"));
			} else {
				// Child tables: DELETE events carry no new state; fall back to the previous state.
				// PatientIdentifier links via patient_id; all other child tables use person_id.
				Map<String, Object> state = newState != null ? newState : event.getPreviousState();
				String fk = PatientIdentifier.class.isAssignableFrom(event.getEntityType()) ? "patient_id" : "person_id";
				personId = (state != null && state.get(fk) != null) ? String.valueOf(state.get(fk)) : null;
			}
			
			if (personId != null) {
				Map<String, Object> headers = new HashMap<>();
				headers.put("personId", personId);
				// We are only interested in changed person_id. We will use OpenMRS API to get
				// fully-hydrated Patient object.
				// Alternatively, we could pass all the changes down and do a differential update to
				// limit the load on DB.
				eventPublisher.publishEvent(new BrokerOutgoingEvent<>(personId, "patientChanges", null, headers));
			}
		}
	}
	
	private Map<String, Object> buildPatientSummary(Patient patient) {
		Map<String, Object> summary = new HashMap<>();
		summary.put("uuid", patient.getUuid());
		summary.put("gender", patient.getGender());
		summary.put("birthdate", patient.getBirthdate());
		summary.put("dead", patient.getDead());
		
		if (patient.getPersonName() != null) {
			Map<String, Object> nameMap = new HashMap<>();
			nameMap.put("givenName", patient.getPersonName().getGivenName());
			nameMap.put("middleName", patient.getPersonName().getMiddleName());
			nameMap.put("familyName", patient.getPersonName().getFamilyName());
			summary.put("name", nameMap);
		}
		
		if (patient.getPersonAddress() != null) {
			Map<String, Object> addressMap = new HashMap<>();
			addressMap.put("cityVillage", patient.getPersonAddress().getCityVillage());
			addressMap.put("stateProvince", patient.getPersonAddress().getStateProvince());
			addressMap.put("country", patient.getPersonAddress().getCountry());
			addressMap.put("postalCode", patient.getPersonAddress().getPostalCode());
			summary.put("address", addressMap);
		}
		
		List<Map<String, Object>> identifiers = new ArrayList<>();
		if (patient.getIdentifiers() != null) {
			for (PatientIdentifier id : patient.getIdentifiers()) {
				if (!Boolean.TRUE.equals(id.getVoided())) {
					Map<String, Object> idMap = new HashMap<>();
					idMap.put("identifier", id.getIdentifier());
					idMap.put("identifierType", id.getIdentifierType() != null ? id.getIdentifierType().getUuid() : null);
					idMap.put("preferred", id.getPreferred());
					identifiers.add(idMap);
				}
			}
		}
		summary.put("identifiers", identifiers);
		
		List<Map<String, Object>> attributes = new ArrayList<>();
		if (patient.getAttributes() != null) {
			for (PersonAttribute attr : patient.getAttributes()) {
				if (!Boolean.TRUE.equals(attr.getVoided())) {
					Map<String, Object> attrMap = new HashMap<>();
					attrMap.put("attributeType", attr.getAttributeType() != null ? attr.getAttributeType().getUuid() : null);
					attrMap.put("value", attr.getValue());
					attributes.add(attrMap);
				}
			}
		}
		summary.put("attributes", attributes);
		
		return summary;
	}
	
	@Override
	public void configure() {
		
		// Catch any exceptions that happen during routing (like Elasticsearch being down)
		// and send the aggregated message to a Dead Letter Queue in Artemis
		onException(Exception.class)
		        .log("Failed to push patient summary for Person ${header.personId} to Elasticsearch. Sending to DLQ.")
		        .to("jms:queue:patientChangesDLQ");
		
		// Consume from Artemis Queue (using the jms component)
		from("jms:queue:patientChanges").id("patient-summary-route")
		        .log("Received change event for Person ${header.personId}")
		        
		        // Debounce: Group by personId and wait for 2 seconds of silence.
		        // By returning 'newExchange', we constantly overwrite the pending message,
		        // effectively dropping the previous ones and keeping only the very last one.
		        // If Tomcat crashes, the server is restarted, or the JVM runs out of memory while Camel is waiting for
		        // the 2-second timeout to complete, the message is permanently lost.
		        // For a 2-second window, this is usually an acceptable risk in many architectures. If strict
		        // zero-data-loss is required, you would need to configure a database-backed JdbcAggregationRepository.
		        .aggregate(header("personId"), (oldExchange, newExchange) -> newExchange).completionTimeout(2000)
		        
		        // Construct the unified Patient Summary document
		        .process(exchange -> {
			        String personId = exchange.getIn().getHeader("personId", String.class);
			        
			        try {
				        Context.openSession();
				        Context.addProxyPrivilege(PrivilegeConstants.GET_PATIENTS);
				        
				        Patient patient = patientService.getPatient(Integer.valueOf(personId));
				        
				        if (patient == null) {
					        // Patient was deleted or does not exist, safely stop routing
					        exchange.setProperty(Exchange.ROUTE_STOP, Boolean.TRUE);
					        return;
				        }
				        
				        Map<String, Object> summary = buildPatientSummary(patient);
				        String summaryDocument = esObjectMapper.writeValueAsString(summary);
				        
				        exchange.getIn().setBody(summaryDocument);
				        // Ensure we assign a clean document ID so ES updates instead of inserting duplicates
				        exchange.getIn().setHeader("indexId", patient.getUuid());
			        }
			        finally {
				        Context.removeProxyPrivilege(PrivilegeConstants.GET_PATIENTS);
				        Context.closeSession();
			        }
		        }).log("Pushing constructed patient summary ${header.indexId} to Elasticsearch")
		        .to("elasticsearch://esCluster?operation=Index&indexName=patient-summary-index");
	}
}
