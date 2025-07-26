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

import java.beans.PropertyDescriptor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.openmrs.module.webservices.rest.web.annotation.PropertyGetter;
import org.openmrs.module.webservices.rest.web.annotation.PropertySetter;
import org.openmrs.module.webservices.rest.web.resource.api.Resource;
import org.openmrs.module.webservices.rest.web.resource.impl.DelegatingResourceHandler;
import org.springframework.beans.BeanUtils;

/**
 * Default implementation of {@link SchemaIntrospectionService}
 */
public class SchemaIntrospectionServiceImpl implements SchemaIntrospectionService {
	
	protected final Logger log = LoggerFactory.getLogger(getClass());
	
	/**
	 * @see org.openmrs.plugin.rest.analyzer.introspection.SchemaIntrospectionService#getDelegateType(Resource)
	 */
	@Override
	public Class<?> getDelegateType(Resource resource) {
		if (resource == null) {
			return null;
		}
		
		if (!(resource instanceof DelegatingResourceHandler)) {
			log.warn("Resource " + resource.getClass().getName() + " is not a DelegatingResourceHandler");
			return null;
		}
		
		Class<?> resourceClass = resource.getClass();
		
		while (resourceClass != null) {
			Type[] genericInterfaces = resourceClass.getGenericInterfaces();
			for (Type genericInterface : genericInterfaces) {
				if (genericInterface instanceof ParameterizedType) {
					ParameterizedType parameterizedType = (ParameterizedType) genericInterface;
					Type rawType = parameterizedType.getRawType();
					
					if (rawType instanceof Class
					        && DelegatingResourceHandler.class.isAssignableFrom((Class<?>) rawType)) {
						Type[] typeArgs = parameterizedType.getActualTypeArguments();
						if (typeArgs.length > 0 && typeArgs[0] instanceof Class) {
							return (Class<?>) typeArgs[0];
						}
					}
				}
			}
			
			Type genericSuperclass = resourceClass.getGenericSuperclass();
			if (genericSuperclass instanceof ParameterizedType) {
				ParameterizedType parameterizedType = (ParameterizedType) genericSuperclass;
				Type[] typeArgs = parameterizedType.getActualTypeArguments();
				
				if (typeArgs.length > 0 && typeArgs[0] instanceof Class) {
					return (Class<?>) typeArgs[0];
				}
			}
			
			resourceClass = resourceClass.getSuperclass();
		}
		
		log.warn("Could not determine delegate type for " + resource.getClass().getName());
		return null;
	}
	
	/**
	 * @see org.openmrs.plugin.rest.analyzer.introspection.SchemaIntrospectionService#discoverAvailableProperties(Class)
	 */
	@Override
	public Map<String, String> discoverAvailableProperties(Class<?> delegateType) {
		if (delegateType == null) {
			return new HashMap<String, String>();
		}
		
		Map<String, String> properties = new HashMap<String, String>();
		
		Class<?> currentClass = delegateType;
		while (currentClass != null && !currentClass.equals(Object.class)) {
			processFields(currentClass, properties);
			currentClass = currentClass.getSuperclass();
		}
		
		PropertyDescriptor[] propertyDescriptors = BeanUtils.getPropertyDescriptors(delegateType);
		for (PropertyDescriptor descriptor : propertyDescriptors) {
			if ("class".equals(descriptor.getName()) || descriptor.getReadMethod() == null) {
				continue;
			}
			
			Method readMethod = descriptor.getReadMethod();
			
			if (Modifier.isPublic(readMethod.getModifiers()) && !Modifier.isStatic(readMethod.getModifiers())) {
				String typeName = getTypeName(readMethod.getGenericReturnType());
				properties.put(descriptor.getName(), typeName);
			}
		}
		
		return properties;
	}
	
	/**
	 * @see org.openmrs.plugin.rest.analyzer.introspection.SchemaIntrospectionService#discoverResourceProperties(Resource)
	 */
	@Override
	public Map<String, String> discoverResourceProperties(Resource resource) {
		Class<?> delegateType = getDelegateType(resource);
		Map<String, String> properties = discoverAvailableProperties(delegateType);
		
		if (resource != null) {
			discoverAnnotatedProperties(resource.getClass(), properties);
		}
		
		return properties;
	}
	
	/**
	 * Discovers properties defined by PropertyGetter and PropertySetter annotations in a resource class
	 * and its superclasses
	 *
	 * @param resourceClass The resource class to scan for annotations
	 * @param properties The map to add discovered properties to
	 */
	private void discoverAnnotatedProperties(Class<?> resourceClass, Map<String, String> properties) {
		Class<?> currentClass = resourceClass;
		while (currentClass != null && !currentClass.equals(Object.class)) {
			for (Method method : currentClass.getDeclaredMethods()) {
				PropertyGetter getter = method.getAnnotation(PropertyGetter.class);
				if (getter != null) {
					String propertyName = getter.value();
					Type returnType = method.getGenericReturnType();
					properties.put(propertyName, getTypeName(returnType));
				}
				
				PropertySetter setter = method.getAnnotation(PropertySetter.class);
				if (setter != null && method.getParameterTypes().length > 1) {
					String propertyName = setter.value();
					Type paramType = method.getGenericParameterTypes()[1];
					if (!properties.containsKey(propertyName)) {
						properties.put(propertyName, getTypeName(paramType));
					}
				}
			}
			currentClass = currentClass.getSuperclass();
		}
	}
	
	/**
	 * Helper method to process fields from a class and add them to the properties map
	 * 
	 * @param clazz The class to process fields from
	 * @param properties The map to add properties to
	 */
	private void processFields(Class<?> clazz, Map<String, String> properties) {
		Field[] fields = clazz.getDeclaredFields();
		for (Field field : fields) {
			if (Modifier.isPublic(field.getModifiers()) && !Modifier.isStatic(field.getModifiers())) {
				String typeName = getTypeName(field.getGenericType());
				properties.put(field.getName(), typeName);
			}
		}
	}
	
	/**
	 * Helper method to get a user-friendly type name from a Type object
	 * 
	 * @param type The type to get a name for
	 * @return A user-friendly type name string
	 */
	private String getTypeName(Type type) {
		if (type instanceof Class) {
			return ((Class<?>) type).getSimpleName();
		} else if (type instanceof ParameterizedType) {
			ParameterizedType paramType = (ParameterizedType) type;
			Type rawType = paramType.getRawType();
			Type[] typeArgs = paramType.getActualTypeArguments();
			
			StringBuilder sb = new StringBuilder();
			if (rawType instanceof Class) {
				sb.append(((Class<?>) rawType).getSimpleName());
			} else {
				sb.append(rawType.toString());
			}
			
			if (typeArgs.length > 0) {
				sb.append("<");
				for (int i = 0; i < typeArgs.length; i++) {
					if (i > 0) {
						sb.append(", ");
					}
					if (typeArgs[i] instanceof Class) {
						sb.append(((Class<?>) typeArgs[i]).getSimpleName());
					} else {
						sb.append(typeArgs[i].toString());
					}
				}
				sb.append(">");
			}
			
			return sb.toString();
		} else {
			return type.toString();
		}
	}
}