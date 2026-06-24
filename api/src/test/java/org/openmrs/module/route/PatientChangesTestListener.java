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

import org.openmrs.event.broker.BrokerOutgoingEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class PatientChangesTestListener {
	
	private final List<BrokerOutgoingEvent<?>> events = new CopyOnWriteArrayList<>();
	
	@EventListener
	public void onEvent(BrokerOutgoingEvent<?> event) {
		events.add(event);
	}
	
	public List<BrokerOutgoingEvent<?>> getEvents() {
		return events;
	}
}
