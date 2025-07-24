package org.openmrs.plugin.rest.analyzer.test;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.openmrs.api.context.Context;
import org.openmrs.GlobalProperty;
import org.openmrs.module.webservices.rest.web.RestConstants;
import org.openmrs.module.webservices.rest.web.annotation.Resource;
import org.openmrs.module.webservices.rest.web.api.RestService;
import org.openmrs.module.webservices.rest.web.resource.impl.DelegatingResourceHandler;
import org.openmrs.module.webservices.rest.web.resource.impl.DelegatingResourceDescription;
import org.openmrs.module.webservices.rest.web.representation.Representation;
import org.openmrs.web.test.jupiter.BaseModuleWebContextSensitiveTest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Collection;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.lang.reflect.ParameterizedType;
import java.beans.PropertyDescriptor;
import java.beans.Introspector;
import java.beans.IntrospectionException;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("OpenAPI 3.0 Generator Test")
public class OpenApiGeneratorTest extends BaseModuleWebContextSensitiveTest {

    private static final Logger log = LoggerFactory.getLogger(OpenApiGeneratorTest.class);

    @BeforeEach
    public void setup() throws Exception {
        log.info("=== Setting up OpenAPI Generator Test ===");
        
        RestService restService = Context.getService(RestService.class);
        assertNotNull(restService, "RestService should be available");
        
        restService.initialize();
        log.info("REST service initialized");
        
        Context.getAdministrationService().saveGlobalProperty(
            new GlobalProperty(RestConstants.SWAGGER_QUIET_DOCS_GLOBAL_PROPERTY_NAME, "true"));
        
        Context.flushSession();
        log.info("=== Setup Complete ===");
    }

    @Test
    @DisplayName("Basic property type detection test")
    public void testPropertyTypeDetection() throws Exception {
        log.info("=== Testing Property Type Detection ===");
        
        RestService restService = Context.getService(RestService.class);
        Collection<DelegatingResourceHandler<?>> handlers = restService.getResourceHandlers();
        
        log.info("Found {} resource handlers", handlers.size());
        assertTrue(handlers.size() > 0, "Should have at least one resource handler");
        
        // Test with first valid resource handler
        for (DelegatingResourceHandler<?> handler : handlers) {
            Resource resourceAnnotation = handler.getClass().getAnnotation(Resource.class);
            if (resourceAnnotation != null) {
                testSingleResourceHandler(handler, resourceAnnotation);
                break; // Only test first one for now
            }
        }
        
        log.info("=== Property Type Detection Test Complete ===");
    }
    
    private void testSingleResourceHandler(DelegatingResourceHandler<?> handler, Resource resourceAnnotation) {
        String resourceName = resourceAnnotation.name();
        Class<?> supportedClass = resourceAnnotation.supportedClass();
        
        log.info("Testing resource: {} ({})", resourceName, supportedClass.getSimpleName());
        
        try {
            DelegatingResourceDescription description = handler.getRepresentationDescription(Representation.DEFAULT);
            if (description != null && description.getProperties() != null) {
                
                log.info("Found {} properties in DEFAULT representation", description.getProperties().size());
                
                // Test property type detection for first few properties
                int count = 0;
                for (Map.Entry<String, DelegatingResourceDescription.Property> entry : 
                        description.getProperties().entrySet()) {
                    
                    if (count >= 3) break; // Only test first 3 properties
                    
                    String propertyName = entry.getKey();
                    DelegatingResourceDescription.Property property = entry.getValue();
                    
                    String detectedType = detectPropertyType(property);
                    
                    log.info("Property '{}': detected type = '{}', required = {}", 
                        propertyName, detectedType, property.isRequired());
                    
                    count++;
                }
            } else {
                log.warn("No DEFAULT representation or properties found for {}", resourceName);
            }
            
        } catch (UnsupportedOperationException e) {
            log.warn("DEFAULT representation not supported for {}", resourceName);
        } catch (Exception e) {
            log.error("Error analyzing {}: {}", resourceName, e.getMessage(), e);
        }
    }
    
    private String detectPropertyType(DelegatingResourceDescription.Property property) {
        // Strategy 1: Check explicit conversion class
        if (property.getConvertAs() != null) {
            String type = mapJavaTypeToOpenApi(property.getConvertAs());
            return type;
        }
        
        // Strategy 2: Check representation level
        if (property.getRep() != null) {
            if (property.getRep() == Representation.REF) {
                return "string"; // REF usually returns uuid/display
            }
            if (property.getRep() == Representation.FULL || property.getRep() == Representation.DEFAULT) {
                return "object"; // Full nested object
            }
        }
        
        // Strategy 3: Check method return type
        if (property.getMethod() != null) {
            String type = mapJavaTypeToOpenApi(property.getMethod().getReturnType());
            return type;
        }
        
        // Strategy 4: Heuristic based on property name
        String type = guessTypeFromPropertyName(property.getDelegateProperty());
        return type;
    }
    
    private String mapJavaTypeToOpenApi(Class<?> javaType) {
        if (javaType == null) return "string";
        
        if (String.class.isAssignableFrom(javaType)) return "string";
        if (Integer.class.isAssignableFrom(javaType) || int.class.equals(javaType)) return "integer";
        if (Long.class.isAssignableFrom(javaType) || long.class.equals(javaType)) return "integer";
        if (Double.class.isAssignableFrom(javaType) || double.class.equals(javaType)) return "number";
        if (Float.class.isAssignableFrom(javaType) || float.class.equals(javaType)) return "number";
        if (Boolean.class.isAssignableFrom(javaType) || boolean.class.equals(javaType)) return "boolean";
        if (java.util.Date.class.isAssignableFrom(javaType)) return "string"; // with format: date-time
        if (java.util.Collection.class.isAssignableFrom(javaType)) return "array";
        
        // Default for complex objects
        return "object";
    }
    
    private String guessTypeFromPropertyName(String propertyName) {
        if (propertyName == null) return "string";
        
        String lowerName = propertyName.toLowerCase();
        
        // Date patterns
        if (lowerName.contains("date") || lowerName.contains("time") || 
            lowerName.equals("birthdate") || lowerName.equals("datecreated")) {
            return "string"; // format: date or date-time
        }
        
        // Boolean patterns  
        if (lowerName.startsWith("is") || lowerName.startsWith("has") || 
            lowerName.equals("voided") || lowerName.equals("retired")) {
            return "boolean";
        }
        
        // Numeric patterns
        if (lowerName.contains("id") && !lowerName.equals("uuid") || 
            lowerName.contains("count") || lowerName.contains("age") || lowerName.contains("weight")) {
            return "integer";
        }
        
        // Array patterns (but be careful with common words)
        if (lowerName.endsWith("s") && !lowerName.equals("address") && 
            !lowerName.equals("class") && !lowerName.equals("links")) {
            return "array";
        }
        
        // Default
        return "string";
    }

    /**
     * HYBRID APPROACH: Representation Analysis + Schema Introspection
     * This is what you wanted - combine representation-based analysis with robust type detection
     */
    @Test
    @DisplayName("Generate OpenAPI with representation analysis and robust type introspection")
    public void testGenerateOpenApiWithHybridApproach() throws Exception {
        log.info("=== Generating OpenAPI with Hybrid Approach ===");
        
        RestService restService = Context.getService(RestService.class);
        Collection<DelegatingResourceHandler<?>> handlers = restService.getResourceHandlers();
        
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        
        ObjectNode jsonOutput = mapper.createObjectNode();
        ObjectNode openApiSpec = createBaseOpenApiStructure(mapper);
        ObjectNode schemas = openApiSpec.with("components").with("schemas");
        
        ArrayNode resourcesArray = mapper.createArrayNode();
        
        int processedCount = 0;
        for (DelegatingResourceHandler<?> handler : handlers) {
            if (processedCount >= 5) break; // Limit for controlled testing
            
            Resource annotation = handler.getClass().getAnnotation(Resource.class);
            if (annotation != null) {
                ObjectNode resourceNode = analyzeResourceWithHybridApproach(mapper, handler, schemas);
                if (resourceNode.has("representations")) {
                    resourcesArray.add(resourceNode);
                    processedCount++;
                }
            }
        }
        
        jsonOutput.set("resources", resourcesArray);
        
        // Write both: enhanced JSON analysis + OpenAPI spec
        writeJsonToFile(mapper, jsonOutput, "hybrid-representation-analysis.json");
        writeJsonToFile(mapper, openApiSpec, "hybrid-openapi-spec.json");
        
        log.info("✓ Generated hybrid analysis with {} resources", processedCount);
        log.info("=== Hybrid Approach Complete ===");
    }
    
    private ObjectNode createBaseOpenApiStructure(ObjectMapper mapper) {
        ObjectNode openApiSpec = mapper.createObjectNode();
        openApiSpec.put("openapi", "3.0.1");
        
        ObjectNode info = mapper.createObjectNode();
        info.put("title", "OpenMRS REST API (Hybrid Analysis)");
        info.put("version", "1.0.0");
        info.put("description", "Generated OpenAPI specification using representation analysis + schema introspection");
        openApiSpec.set("info", info);
        
        ObjectNode components = mapper.createObjectNode();
        ObjectNode schemas = mapper.createObjectNode();
        components.set("schemas", schemas);
        openApiSpec.set("components", components);
        
        return openApiSpec;
    }
    
    private ObjectNode analyzeResourceWithHybridApproach(ObjectMapper mapper, 
            DelegatingResourceHandler<?> handler, ObjectNode schemas) {
        
        ObjectNode resourceNode = mapper.createObjectNode();
        Resource annotation = handler.getClass().getAnnotation(Resource.class);
        
        if (annotation != null) {
            String resourceName = annotation.name();
            Class<?> delegateType = getDelegateTypeFromResource(handler); // Schema introspection
            
            resourceNode.put("resourceName", resourceName);
            resourceNode.put("supportedClass", annotation.supportedClass().getSimpleName());
            
            if (delegateType != null) {
                resourceNode.put("delegateType", delegateType.getSimpleName());
            }
            
            // Analyze each representation separately (your original approach)
            ObjectNode representations = mapper.createObjectNode();
            
            // DEFAULT representation
            ObjectNode defaultRep = analyzeRepresentationWithTypes(mapper, handler, Representation.DEFAULT, delegateType);
            representations.set("DEFAULT", defaultRep);
            if (defaultRep.has("properties")) {
                generateOpenApiSchema(schemas, resourceName + "_DEFAULT", defaultRep, mapper);
            }
            
            // FULL representation  
            ObjectNode fullRep = analyzeRepresentationWithTypes(mapper, handler, Representation.FULL, delegateType);
            representations.set("FULL", fullRep);
            if (fullRep.has("properties")) {
                generateOpenApiSchema(schemas, resourceName + "_FULL", fullRep, mapper);
            }
            
            // REF representation
            ObjectNode refRep = analyzeRepresentationWithTypes(mapper, handler, Representation.REF, delegateType);
            representations.set("REF", refRep);
            if (refRep.has("properties")) {
                generateOpenApiSchema(schemas, resourceName + "_REF", refRep, mapper);
            }
            
            resourceNode.set("representations", representations);
        }
        
        return resourceNode;
    }
    
    private ObjectNode analyzeRepresentationWithTypes(ObjectMapper mapper, 
            DelegatingResourceHandler<?> handler, Representation representation, Class<?> delegateType) {
        
        try {
            DelegatingResourceDescription description = handler.getRepresentationDescription(representation);
            
            if (description == null) {
                ObjectNode errorNode = mapper.createObjectNode();
                errorNode.put("error", "Representation not supported");
                return errorNode;
            }
            
            ObjectNode repNode = mapper.createObjectNode();
            int propertyCount = (description.getProperties() != null) ? description.getProperties().size() : 0;
            int linkCount = (description.getLinks() != null) ? description.getLinks().size() : 0;
            
            repNode.put("propertyCount", propertyCount);
            repNode.put("linkCount", linkCount);
            
            // NEW: Extract property names and types using schema introspection
            if (description.getProperties() != null) {
                ObjectNode properties = mapper.createObjectNode();
                
                for (Map.Entry<String, DelegatingResourceDescription.Property> entry : 
                        description.getProperties().entrySet()) {
                    
                    String propertyName = entry.getKey();
                    DelegatingResourceDescription.Property property = entry.getValue();
                    
                    ObjectNode propertyInfo = mapper.createObjectNode();
                    
                    // Use robust type detection from schema introspection
                    String javaType = detectPropertyTypeUsingIntrospection(property, delegateType, propertyName);
                    String openApiType = mapJavaTypeToOpenApiType(javaType);
                    
                    propertyInfo.put("javaType", javaType);
                    propertyInfo.put("openApiType", openApiType);
                    propertyInfo.put("required", property.isRequired());
                    
                    // Add representation-specific info
                    if (property.getRep() != null) {
                        propertyInfo.put("representationLevel", property.getRep().getRepresentation());
                    }
                    
                    properties.set(propertyName, propertyInfo);
                }
                
                repNode.set("properties", properties);
            }
            
            return repNode;
            
        } catch (UnsupportedOperationException e) {
            ObjectNode errorNode = mapper.createObjectNode();
            errorNode.put("error", "Representation not supported");
            return errorNode;
        }
    }
    
    /**
     * NEW: Detect property type using schema introspection approach
     * This combines representation property info with actual class introspection
     */
    private String detectPropertyTypeUsingIntrospection(DelegatingResourceDescription.Property property, 
            Class<?> delegateType, String propertyName) {
        
        // First try to get type from the actual delegate class (most accurate)
        if (delegateType != null) {
            String typeFromClass = getPropertyTypeFromClass(delegateType, propertyName);
            if (typeFromClass != null) {
                return typeFromClass;
            }
        }
        
        // Fallback to representation-based detection
        if (property.getConvertAs() != null) {
            return property.getConvertAs().getSimpleName();
        }
        
        if (property.getMethod() != null) {
            return getTypeName(property.getMethod().getGenericReturnType());
        }
        
        // Last resort - name heuristics
        return guessJavaTypeFromPropertyName(propertyName);
    }
    
    /**
     * Get property type from actual class using schema introspection logic
     */
    private String getPropertyTypeFromClass(Class<?> clazz, String propertyName) {
        try {
            // Check bean properties first
            PropertyDescriptor[] descriptors = Introspector.getBeanInfo(clazz).getPropertyDescriptors();
            for (PropertyDescriptor descriptor : descriptors) {
                if (propertyName.equals(descriptor.getName()) && descriptor.getReadMethod() != null) {
                    return getTypeName(descriptor.getReadMethod().getGenericReturnType());
                }
            }
            
            // Check fields
            Field field = findField(clazz, propertyName);
            if (field != null) {
                return getTypeName(field.getGenericType());
            }
            
        } catch (IntrospectionException e) {
            log.debug("Error introspecting {}: {}", clazz.getSimpleName(), e.getMessage());
        }
        
        return null;
    }
    
    private Field findField(Class<?> clazz, String fieldName) {
        Class<?> current = clazz;
        while (current != null && !current.equals(Object.class)) {
            try {
                Field field = current.getDeclaredField(fieldName);
                if (Modifier.isPublic(field.getModifiers()) && !Modifier.isStatic(field.getModifiers())) {
                    return field;
                }
            } catch (NoSuchFieldException e) {
                // Continue searching in parent class
            }
            current = current.getSuperclass();
        }
        return null;
    }
    
    /**
     * Get type name from Type (from SchemaIntrospectionService)
     */
    private String getTypeName(Type type) {
        if (type instanceof Class) {
            return ((Class<?>) type).getSimpleName();
        } else if (type instanceof ParameterizedType) {
            ParameterizedType parameterizedType = (ParameterizedType) type;
            Type rawType = parameterizedType.getRawType();
            Type[] actualTypeArguments = parameterizedType.getActualTypeArguments();
            
            if (rawType instanceof Class) {
                String rawTypeName = ((Class<?>) rawType).getSimpleName();
                if (actualTypeArguments.length > 0) {
                    List<String> typeArgNames = new ArrayList<>();
                    for (Type argType : actualTypeArguments) {
                        typeArgNames.add(getTypeName(argType));
                    }
                    return rawTypeName + "<" + String.join(", ", typeArgNames) + ">";
                }
                return rawTypeName;
            }
        }
        
        return type.toString();
    }
    
    /**
     * Extract delegate type from resource handler (from SchemaIntrospectionService)
     */
    private Class<?> getDelegateTypeFromResource(DelegatingResourceHandler<?> handler) {
        Class<?> resourceClass = handler.getClass();
        
        while (resourceClass != null) {
            Type[] genericInterfaces = resourceClass.getGenericInterfaces();
            for (Type genericInterface : genericInterfaces) {
                if (genericInterface instanceof ParameterizedType) {
                    ParameterizedType parameterizedType = (ParameterizedType) genericInterface;
                    Type rawType = parameterizedType.getRawType();
                    
                    if (rawType instanceof Class && 
                        DelegatingResourceHandler.class.isAssignableFrom((Class<?>) rawType)) {
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
        
        return null;
    }
    
    private String mapJavaTypeToOpenApiType(String javaType) {
        if (javaType == null) return "string";
        
        if (javaType.equals("String")) return "string";
        if (javaType.equals("Integer") || javaType.equals("Long")) return "integer";
        if (javaType.equals("Double") || javaType.equals("Float")) return "number";
        if (javaType.equals("Boolean")) return "boolean";
        if (javaType.equals("Date") || javaType.contains("Date")) return "string";
        if (javaType.startsWith("List<") || javaType.startsWith("Set<") || javaType.startsWith("Collection<")) return "array";
        
        return "object";
    }
    
    private String guessJavaTypeFromPropertyName(String propertyName) {
        if (propertyName == null) return "String";
        
        String lowerName = propertyName.toLowerCase();
        
        if (lowerName.contains("date") || lowerName.contains("time")) return "Date";
        if (lowerName.startsWith("is") || lowerName.equals("voided") || lowerName.equals("retired")) return "Boolean";
        if (lowerName.contains("id") && !lowerName.equals("uuid")) return "Integer";
        if (lowerName.endsWith("s") && !lowerName.equals("address")) return "List<String>";
        
        return "String";
    }
    
    private void generateOpenApiSchema(ObjectNode schemas, String schemaName, ObjectNode repData, ObjectMapper mapper) {
        if (!repData.has("properties")) return;
        
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        schema.put("description", "Schema for " + schemaName);
        
        ObjectNode properties = mapper.createObjectNode();
        ObjectNode repProperties = (ObjectNode) repData.get("properties");
        
        repProperties.fields().forEachRemaining(entry -> {
            String propName = entry.getKey();
            ObjectNode propInfo = (ObjectNode) entry.getValue();
            
            String javaType = propInfo.get("javaType").asText();
            String openApiType = propInfo.get("openApiType").asText();
            
            ObjectNode propSchema = createPropertySchemaWithProperRefs(mapper, propName, javaType, openApiType, schemas);
            properties.set(propName, propSchema);
        });
        
        schema.set("properties", properties);
        schemas.set(schemaName, schema);
    }
    
    /**
     * Create property schema with proper $ref handling for OpenMRS domain types
     * This generates the output you want like: "voidedBy": {"$ref": "#/components/schemas/User"}
     */
    private ObjectNode createPropertySchemaWithProperRefs(ObjectMapper mapper, String propName, String javaType, String openApiType, ObjectNode schemas) {
        ObjectNode propSchema = mapper.createObjectNode();
        
        // Handle OpenMRS domain types with $ref (like User, Patient, Encounter)
        if (isOpenMRSDomainType(javaType)) {
            String refName = cleanTypeNameForRef(javaType);
            propSchema.put("$ref", "#/components/schemas/" + refName);
            
            // Ensure the referenced type schema exists
            if (!schemas.has(refName)) {
                createReferencedTypeSchema(mapper, schemas, refName, javaType);
            }
            
            return propSchema;
        }
        
        // Handle collections with proper array typing and $ref for complex items
        if (javaType.startsWith("List<") || javaType.startsWith("Set<") || javaType.startsWith("Collection<")) {
            propSchema.put("type", "array");
            String itemType = extractGenericType(javaType);
            
            ObjectNode items = mapper.createObjectNode();
            if (isOpenMRSDomainType(itemType)) {
                String refName = cleanTypeNameForRef(itemType);
                items.put("$ref", "#/components/schemas/" + refName);
                
                // Ensure the referenced type schema exists
                if (!schemas.has(refName)) {
                    createReferencedTypeSchema(mapper, schemas, refName, itemType);
                }
            } else {
                items.put("type", mapJavaTypeToOpenApiType(itemType));
                if (itemType.equals("Date") || itemType.contains("Date")) {
                    items.put("format", "date-time");
                }
            }
            propSchema.set("items", items);
            propSchema.put("description", "Array of " + itemType);
            return propSchema;
        }
        
        // Handle primitive and simple types
        propSchema.put("type", openApiType);
        
        // Add format for dates
        if (javaType.equals("Date") || javaType.contains("Date")) {
            propSchema.put("format", "date-time");
        }
        
        // Add description
        propSchema.put("description", propName + " (" + javaType + ")");
        
        return propSchema;
    }
    
    /**
     * Check if a Java type is an OpenMRS domain type that should be referenced with $ref
     */
    private boolean isOpenMRSDomainType(String javaType) {
        return javaType.equals("Patient") || javaType.equals("Person") || javaType.equals("Encounter") || 
               javaType.equals("Concept") || javaType.equals("Location") || javaType.equals("User") ||
               javaType.equals("Provider") || javaType.equals("Visit") || javaType.contains("Attribute") ||
               javaType.equals("Program") || javaType.equals("ConceptClass") || javaType.equals("Condition") ||
               javaType.contains("PatientState") || javaType.contains("PatientProgram") || 
               javaType.contains("ConditionVerificationStatus") || javaType.contains("ConditionClinicalStatus") ||
               javaType.contains("CodedOrFreeText") || javaType.contains("CareSettingType") ||
               javaType.contains("ConceptStateConversion") || javaType.contains("VisitType") ||
               javaType.contains("ProgramWorkflowState") || javaType.contains("WorkflowState");
    }
    
    /**
     * Clean type name for $ref usage (remove generics brackets, etc.)
     */
    private String cleanTypeNameForRef(String javaType) {
        return javaType.replaceAll("[<>]", "").replace("[]", "Array");
    }
    
    /**
     * Create a referenced type schema for $ref targets
     */
    private void createReferencedTypeSchema(ObjectMapper mapper, ObjectNode schemas, String refName, String javaType) {
        ObjectNode typeSchema = mapper.createObjectNode();
        typeSchema.put("type", "object");
        typeSchema.put("description", "Schema for " + refName + " (Java type: " + javaType + ")");
        
        // Add common properties that most OpenMRS domain objects have
        ObjectNode typeProperties = mapper.createObjectNode();
        
        ObjectNode idProperty = mapper.createObjectNode();
        idProperty.put("type", "integer");
        idProperty.put("description", "Unique identifier");
        typeProperties.set("id", idProperty);
        
        ObjectNode uuidProperty = mapper.createObjectNode();
        uuidProperty.put("type", "string");
        uuidProperty.put("description", "Universally unique identifier");
        typeProperties.set("uuid", uuidProperty);
        
        ObjectNode displayProperty = mapper.createObjectNode();
        displayProperty.put("type", "string");
        displayProperty.put("description", "Display representation");
        typeProperties.set("display", displayProperty);
        
        typeSchema.set("properties", typeProperties);
        schemas.set(refName, typeSchema);
        
        log.debug("Created referenced type schema for: {}", refName);
    }
    
    /**
     * Extract generic type from parameterized type string
     */
    private String extractGenericType(String parameterizedType) {
        if (parameterizedType.contains("<") && parameterizedType.contains(">")) {
            int start = parameterizedType.indexOf("<") + 1;
            int end = parameterizedType.lastIndexOf(">");
            String genericType = parameterizedType.substring(start, end);
            
            // Handle nested generics by taking the first type
            if (genericType.contains(",")) {
                genericType = genericType.split(",")[0].trim();
            }
            
            return genericType;
        }
        
        return "string"; // Default
    }
    
    private void writeJsonToFile(ObjectMapper mapper, ObjectNode jsonNode, String filename) {
        try {
            File outputDir = new File("target");
            if (!outputDir.exists()) {
                outputDir.mkdirs();
            }
            
            File outputFile = new File(outputDir, filename);
            mapper.writeValue(outputFile, jsonNode);
            
            log.info("✓ Saved: {}", outputFile.getAbsolutePath());
            
        } catch (Exception e) {
            log.error("Failed to write {}: {}", filename, e.getMessage());
        }
    }
}