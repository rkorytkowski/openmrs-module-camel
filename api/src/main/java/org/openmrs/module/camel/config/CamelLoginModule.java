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

import javax.security.auth.Subject;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.login.LoginException;
import javax.security.auth.spi.LoginModule;
import java.security.Principal;
import java.util.Map;

public class CamelLoginModule implements LoginModule {
	
	private Subject subject;
	
	private CallbackHandler callbackHandler;
	
	private String expectedUsername;
	
	private String expectedPassword;
	
	private boolean authenticated = false;
	
	@Override
	public void initialize(Subject subject, CallbackHandler callbackHandler, Map<String, ?> sharedState,
	        Map<String, ?> options) {
		this.subject = subject;
		this.callbackHandler = callbackHandler;
		this.expectedUsername = (String) options.get("username");
		this.expectedPassword = (String) options.get("password");
	}
	
	@Override
	public boolean login() throws LoginException {
		if (callbackHandler == null) {
			throw new LoginException("No CallbackHandler available");
		}
		
		Callback[] callbacks = new Callback[] { new NameCallback("username"), new PasswordCallback("password", false) };
		try {
			callbackHandler.handle(callbacks);
			String user = ((NameCallback) callbacks[0]).getName();
			char[] pass = ((PasswordCallback) callbacks[1]).getPassword();
			String password = pass == null ? "" : new String(pass);
			
			if (expectedUsername != null && expectedUsername.equals(user) && expectedPassword != null
			        && expectedPassword.equals(password)) {
				authenticated = true;
				return true;
			}
		}
		catch (Exception e) {
			throw new LoginException(e.getMessage());
		}
		return false;
	}
	
	@Override
	public boolean commit() throws LoginException {
		if (authenticated) {
			subject.getPrincipals().add(new Principal() {
				
				@Override
				public String getName() {
					return expectedUsername;
				}
			});
			// Grant the admin role expected by Hawtio
			subject.getPrincipals().add(new Principal() {
				
				@Override
				public String getName() {
					return "admin";
				}
			});
			return true;
		}
		return false;
	}
	
	@Override
	public boolean abort() throws LoginException {
		authenticated = false;
		return true;
	}
	
	@Override
	public boolean logout() throws LoginException {
		authenticated = false;
		subject.getPrincipals().clear();
		return true;
	}
}
