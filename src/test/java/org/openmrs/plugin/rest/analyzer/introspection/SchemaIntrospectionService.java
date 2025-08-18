/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.plugin.rest.analyzer.introspection;

import java.util.Map;

import org.openmrs.module.webservices.rest.web.resource.api.Resource;
import org.openmrs.module.webservices.rest.web.resource.impl.DelegatingResourceDescription;
import org.openmrs.module.webservices.rest.web.resource.impl.DelegatingResourceHandler;

/**
 * Service for introspecting resource properties via reflection.
 * This enables clients to discover all properties that may be available in custom representations.
 */
public interface SchemaIntrospectionService {
	
	/**
	 * Gets the delegate type (T) for a resource that extends DelegatingCrudResource<T>.
	 * 
	 * @param resource The resource whose delegate class we want to determine
	 * @return The class type of the delegate, or null if not determinable
	 */
	Class<?> getDelegateType(Resource resource);
	
	/**
	 * Discovers all available properties on a delegate type using reflection.
	 * This includes public instance fields and JavaBean-style getter methods.
	 * 
	 * @param delegateType The class to introspect for properties
	 * @return A map of property names to their Java type names
	 */
	Map<String, String> discoverAvailableProperties(Class<?> delegateType);
	
	/**
	 * Combines getDelegateType and discoverAvailableProperties for convenience.
	 * 
	 * @param resource The resource to introspect
	 * @return A map of property names to their Java type names
	 */
	Map<String, String> discoverResourceProperties(Resource resource);
	
	/**
	 * Determines the most accurate property type using multiple resolution strategies:
	 * 1. Introspected types from delegate classes (highest priority)
	 * 2. Representation metadata from DelegatingResourceDescription.Property
	 * 3. Reflection on delegate class properties
	 * 4. Intelligent inference from property names
	 * 5. Fallback to generic types
	 * 
	 * @param propertyName The name of the property
	 * @param property The DelegatingResourceDescription.Property containing representation metadata
	 * @param handler The resource handler
	 * @param introspectedProperties Map of properties discovered via reflection on delegate class
	 * @return The most accurate type string for this property
	 */
	String determineAccuratePropertyType(String propertyName, 
	                                   DelegatingResourceDescription.Property property,
	                                   DelegatingResourceHandler<?> handler,
	                                   Map<String, String> introspectedProperties);
}