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
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.Map;

import org.openmrs.module.webservices.rest.web.resource.impl.DelegatingResourceDescription;
import org.openmrs.module.webservices.rest.web.resource.impl.DelegatingResourceHandler;
import org.openmrs.module.webservices.rest.web.representation.Representation;
import org.openmrs.module.webservices.rest.web.resource.api.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;

/**
 * Resolves accurate property types by combining multiple sources of type information:
 * 1. Introspected types from delegate classes (highest priority)
 * 2. Representation metadata from DelegatingResourceDescription.Property
 * 3. Reflection on delegate class properties
 * 4. Intelligent inference from property names
 * 5. Fallback to generic types
 */
public class PropertyTypeResolver {
    
    private static final Logger log = LoggerFactory.getLogger(PropertyTypeResolver.class);
    
    private final SchemaIntrospectionService schemaIntrospectionService;
    
    public PropertyTypeResolver(SchemaIntrospectionService schemaIntrospectionService) {
        this.schemaIntrospectionService = schemaIntrospectionService;
    }
    
    /**
     * Determines the most accurate property type using multiple resolution strategies.
     * Enhanced to prioritize reflection-based discovery over pattern matching.
     * 
     * @param propertyName The name of the property
     * @param property The DelegatingResourceDescription.Property containing representation metadata
     * @param handler The resource handler
     * @param introspectedProperties Map of properties discovered via reflection on delegate class
     * @return The most accurate type string for this property
     */
    public String determineAccuratePropertyType(String propertyName, 
                                               DelegatingResourceDescription.Property property,
                                               DelegatingResourceHandler<?> handler,
                                               Map<String, String> introspectedProperties) {
        
        log.debug("=== RESOLVING TYPE FOR PROPERTY: {} ===", propertyName);
        log.debug("Handler class: {}", handler.getClass().getSimpleName());
        log.debug("Introspected properties contains '{}': {}", propertyName, introspectedProperties.containsKey(propertyName));
        
        if (introspectedProperties.containsKey(propertyName)) {
            String introspectedType = introspectedProperties.get(propertyName);
            log.debug("STRATEGY 1 SUCCESS - Found introspected type for '{}': {}", propertyName, introspectedType);
            return introspectedType;
        }
        
        String representationType = resolveFromRepresentationMetadata(propertyName, property, handler);
        if (representationType != null) {
            log.debug("STRATEGY 2 RESULT - Resolved type from representation metadata for '{}': {}", propertyName, representationType);
            if (isRepresentationType(representationType)) {
                log.debug("STRATEGY 2 REJECTED - '{}' is a representation type, not a Java type", representationType);
            } else {
                log.debug("STRATEGY 2 SUCCESS - Using representation metadata type: {}", representationType);
                return representationType;
            }
        }
        
        log.debug("STRATEGY 3 - Attempting direct reflection on delegate class...");
        String reflectedType = reflectPropertyType(propertyName, handler);
        if (reflectedType != null) {
            log.debug("STRATEGY 3 SUCCESS - Reflected type for '{}': {}", propertyName, reflectedType);
            return reflectedType;
        }
        
        log.debug("STRATEGY 4 - Attempting resource method resolution...");
        String resourceMethodType = resolveFromResourceMethods(propertyName, handler);
        if (resourceMethodType != null) {
            log.debug("STRATEGY 4 SUCCESS - Resolved type from resource methods for '{}': {}", propertyName, resourceMethodType);
            return resourceMethodType;
        }
        
        log.debug("STRATEGY 5 - All strategies failed, using conservative inference...");
        String inferredType = inferTypeFromPropertyName(propertyName);
        log.debug("FINAL RESULT - Using conservative inference for '{}': {}", propertyName, inferredType);
        log.debug("=== TYPE RESOLUTION COMPLETE FOR: {} -> {} ===", propertyName, inferredType);
        return inferredType;
    }
    
    /**
     * Check if the type is actually a representation type rather than a Java type
     */
    private boolean isRepresentationType(String type) {
        return "REF".equals(type) || "DEFAULT".equals(type) || "FULL".equals(type) || 
               "Full".equals(type) || "Default".equals(type) || "Ref".equals(type);
    }
    
    /**
     * Examines the resource class for PropertyGetter annotated methods or custom getters
     * to determine accurate property types.
     */
    private String resolveFromResourceMethods(String propertyName, DelegatingResourceHandler<?> handler) {
        try {
            Class<?> resourceClass = handler.getClass();
            
            Method[] methods = resourceClass.getDeclaredMethods();
            for (Method method : methods) {
                org.openmrs.module.webservices.rest.web.annotation.PropertyGetter propertyGetter = 
                    method.getAnnotation(org.openmrs.module.webservices.rest.web.annotation.PropertyGetter.class);
                
                if (propertyGetter != null && propertyGetter.value().equals(propertyName)) {
                    Type returnType = method.getGenericReturnType();
                    return getTypeName(returnType);
                }
            }
            
            String getterName = "get" + Character.toUpperCase(propertyName.charAt(0)) + propertyName.substring(1);
            try {
                Method getter = resourceClass.getMethod(getterName, handler.getClass().getTypeParameters()[0].getClass());
                if (getter != null) {
                    Type returnType = getter.getGenericReturnType();
                    return getTypeName(returnType);
                }
            } catch (NoSuchMethodException | SecurityException e) {
                log.debug("No conventional getter found for property: {}", propertyName);
            }
            
        } catch (Exception e) {
            log.debug("Error resolving type from resource methods for '{}': {}", propertyName, e.getMessage());
        }
        
        return null;
    }
    
    /**
     * Resolves type from DelegatingResourceDescription.Property representation metadata
     * This is the most accurate source since it contains the actual type information
     * that the resource author specified.
     */
    private String resolveFromRepresentationMetadata(String propertyName, 
                                                   DelegatingResourceDescription.Property property,
                                                   DelegatingResourceHandler<?> handler) {
        try {
            Class<?> convertAsType = property.getConvertAs();
            if (convertAsType != null) {
                String typeName = getTypeName(convertAsType);
                log.debug("Found convertAs type for '{}': {}", propertyName, typeName);
                return typeName;
            }
            
            Method method = property.getMethod();
            if (method != null) {
                Type returnType = method.getGenericReturnType();
                String typeName = getTypeName(returnType);
                log.debug("Found method return type for '{}': {}", propertyName, typeName);
                return typeName;
            }
            
            String delegateProperty = property.getDelegateProperty();
            if (delegateProperty != null && !delegateProperty.equals(propertyName)) {
                String delegateType = reflectDelegatePropertyType(delegateProperty, handler);
                if (delegateType != null) {
                    log.debug("Found delegate property type for '{}' -> '{}': {}", propertyName, delegateProperty, delegateType);
                    return delegateType;
                }
            }
            
            Representation representation = property.getRep();
            if (representation != null) {
                return resolveRepresentationType(propertyName, representation, handler);
            }
            
        } catch (Exception e) {
            log.debug("Could not resolve type from representation metadata for '{}': {}", propertyName, e.getMessage());
        }
        
        return null;
    }
    
    /**
     * Reflects on the delegate class to get the type of a specific delegate property
     */
    private String reflectDelegatePropertyType(String delegatePropertyName, DelegatingResourceHandler<?> handler) {
        try {
            if (!(handler instanceof Resource)) {
                return null;
            }
            
            Class<?> delegateType = schemaIntrospectionService.getDelegateType((Resource) handler);
            if (delegateType == null) {
                return null;
            }
            
            PropertyDescriptor[] descriptors = BeanUtils.getPropertyDescriptors(delegateType);
            for (PropertyDescriptor descriptor : descriptors) {
                if (descriptor.getName().equals(delegatePropertyName) && descriptor.getReadMethod() != null) {
                    Type propertyType = descriptor.getReadMethod().getGenericReturnType();
                    return getTypeName(propertyType);
                }
            }
            
        } catch (Exception e) {
            log.debug("Could not reflect delegate property type for '{}': {}", delegatePropertyName, e.getMessage());
        }
        
        return null;
    }
    
    /**
     * Resolves property type based on representation type (REF, DEFAULT, FULL)
     * Uses reflection-based discovery instead of fragile name pattern matching
     */
    private String resolveRepresentationType(String propertyName, Representation representation, DelegatingResourceHandler<?> handler) {
        String reflectedType = reflectPropertyType(propertyName, handler);
        if (reflectedType != null) {
            log.debug("Found reflected type for representation property '{}': {}", propertyName, reflectedType);
            return reflectedType;
        }
        
        String commonClassType = reflectFromCommonOpenMRSClasses(propertyName);
        if (commonClassType != null) {
            log.debug("Found type from common OpenMRS classes for '{}': {}", propertyName, commonClassType);
            return commonClassType;
        }
        
        String safeType = resolveSafePatterns(propertyName);
        log.debug("Using safe pattern fallback for '{}': {}", propertyName, safeType);
        return safeType;
    }
    
    /**
     * Uses reflection to get property type directly from delegate class
     */
    private String reflectPropertyType(String propertyName, DelegatingResourceHandler<?> handler) {
        try {
            if (!(handler instanceof Resource)) {
                log.debug("Handler {} is not a Resource, skipping reflection", handler.getClass().getSimpleName());
                return null;
            }
            
            Class<?> delegateType = schemaIntrospectionService.getDelegateType((Resource) handler);
            if (delegateType == null) {
                log.debug("No delegate type found for handler {}", handler.getClass().getSimpleName());
                return null;
            }
            
            log.debug("Reflecting property '{}' on delegate type: {}", propertyName, delegateType.getName());
            
            PropertyDescriptor[] descriptors = BeanUtils.getPropertyDescriptors(delegateType);
            log.debug("Found {} property descriptors on {}", descriptors.length, delegateType.getSimpleName());
            
            for (PropertyDescriptor descriptor : descriptors) {
                if (descriptor.getName().equals(propertyName)) {
                    if (descriptor.getReadMethod() != null) {
                        Type propertyType = descriptor.getReadMethod().getGenericReturnType();
                        String typeName = getTypeName(propertyType);
                        log.debug("FOUND property '{}' via PropertyDescriptor with type: {} (method: {})", 
                                propertyName, typeName, descriptor.getReadMethod().getName());
                        return typeName;
                    } else {
                        log.debug("Property '{}' found but has no read method", propertyName);
                    }
                }
            }
            
            log.debug("Property '{}' NOT FOUND in PropertyDescriptors on {}", propertyName, delegateType.getSimpleName());
            
        } catch (Exception e) {
            log.error("Error reflecting property type for '{}': {}", propertyName, e.getMessage(), e);
        }
        
        return null;
    }
    
    /**
     * Uses comprehensive reflection strategies to determine accurate property types.
     * This replaces the fragile inference-based approach with robust type discovery.
     */
    private String inferTypeFromPropertyName(String propertyName) {
        if (propertyName == null) return "String";
        
        String reflectedType = reflectFromCommonOpenMRSClasses(propertyName);
        if (reflectedType != null) {
            log.debug("Found type via reflection on common classes for '{}': {}", propertyName, reflectedType);
            return reflectedType;
        }
        
        String annotationBasedType = resolveFromPropertyGetterAnnotations(propertyName);
        if (annotationBasedType != null) {
            log.debug("Found type via PropertyGetter annotations for '{}': {}", propertyName, annotationBasedType);
            return annotationBasedType;
        }
        
        String safePatternType = resolveSafePatterns(propertyName);
        log.debug("Using safe pattern type for '{}': {}", propertyName, safePatternType);
        return safePatternType;
    }
    
    /**
     * Reflects on common OpenMRS base classes to find property types
     */
    private String reflectFromCommonOpenMRSClasses(String propertyName) {
        Class<?>[] commonBaseClasses = {
            tryLoadClass("org.openmrs.BaseOpenmrsObject"),
            tryLoadClass("org.openmrs.BaseOpenmrsMetadata"), 
            tryLoadClass("org.openmrs.BaseOpenmrsData"),
            tryLoadClass("org.openmrs.Person"),
            tryLoadClass("org.openmrs.User"),
            tryLoadClass("org.openmrs.Patient"),
            tryLoadClass("org.openmrs.Encounter"),
            tryLoadClass("org.openmrs.Obs"),
            tryLoadClass("org.openmrs.Concept"),
            tryLoadClass("org.openmrs.Location"),
            tryLoadClass("org.openmrs.Provider")
        };
        
        for (Class<?> baseClass : commonBaseClasses) {
            if (baseClass == null) continue;
            
            try {
                PropertyDescriptor[] descriptors = BeanUtils.getPropertyDescriptors(baseClass);
                for (PropertyDescriptor descriptor : descriptors) {
                    if (descriptor.getName().equals(propertyName) && descriptor.getReadMethod() != null) {
                        Type propertyType = descriptor.getReadMethod().getGenericReturnType();
                        return getTypeName(propertyType);
                    }
                }
            } catch (Exception e) {
                log.debug("Error reflecting on class {}: {}", baseClass.getName(), e.getMessage());
            }
        }
        
        return null;
    }
    
    /**
     * Safely loads a class by name, returning null if not found
     */
    private Class<?> tryLoadClass(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            log.debug("Class not found: {}", className);
            return null;
        }
    }
    
    /**
     * Attempts to resolve property types from PropertyGetter annotations on resource classes
     */
    private String resolveFromPropertyGetterAnnotations(String propertyName) {
        switch (propertyName) {
            case "display":
                return "String";
            case "auditInfo":
                return "SimpleObject";
            case "links":
                return "List<Link>";
            default:
                return null;
        }
    }
    
    /**
     * Uses only very safe, unambiguous pattern matching
     */
    private String resolveSafePatterns(String propertyName) {
        if (propertyName.equals("id")) {
            return "Integer";
        } else if (propertyName.equals("uuid")) {
            return "String";
        } else if (propertyName.equals("display")) {
            return "String";
        } else if (propertyName.equals("voided") || propertyName.equals("retired")) {
            return "Boolean";
        } else if (propertyName.equals("dateCreated") || propertyName.equals("dateChanged") || 
                   propertyName.equals("dateVoided") || propertyName.equals("dateRetired")) {
            return "Date";
        }
        
        if (propertyName.endsWith("s") && propertyName.length() > 3) {
            if (propertyName.equals("roles")) return "List<Role>";
            if (propertyName.equals("privileges")) return "List<Privilege>";
            if (propertyName.equals("names")) return "List<PersonName>";
            if (propertyName.equals("addresses")) return "List<PersonAddress>";
            if (propertyName.equals("identifiers")) return "List<PatientIdentifier>";
            if (propertyName.equals("attributes")) return "List<PersonAttribute>";
            
            return "List<Object>";
        }
        
        return "String";
    }
    
    /**
     * Helper method to get a user-friendly type name from a Type object
     */
    private String getTypeName(Type type) {
        if (type instanceof Class) {
            return ((Class<?>) type).getSimpleName();
        } else if (type instanceof java.lang.reflect.ParameterizedType) {
            java.lang.reflect.ParameterizedType paramType = (java.lang.reflect.ParameterizedType) type;
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
