package org.openmrs.plugin.rest.analyzer.test;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openmrs.GlobalProperty;
import org.openmrs.api.context.Context;
import org.openmrs.module.webservices.rest.web.RestConstants;
import org.openmrs.module.webservices.rest.web.annotation.Resource;
import org.openmrs.module.webservices.rest.web.api.RestService;
import org.openmrs.module.webservices.rest.web.resource.impl.DelegatingResourceDescription;
import org.openmrs.module.webservices.rest.web.resource.impl.DelegatingResourceHandler;
import org.openmrs.module.webservices.rest.web.representation.Representation;
import org.openmrs.web.test.jupiter.BaseModuleWebContextSensitiveTest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("OpenMRS OpenAPI Spec Generator Test")
public class OpenmrsOpenapiSpecGeneratorTest extends BaseModuleWebContextSensitiveTest {
    private static final Logger log = LoggerFactory.getLogger(OpenmrsOpenapiSpecGeneratorTest.class);
    private static final String OUTPUT_FILE = "openapi-spec.json";

    @BeforeEach
    public void setup() throws Exception {
        log.info("=== Setting up OpenAPI Spec Generator Test ===");
        RestService restService = Context.getService(RestService.class);
        assertNotNull(restService, "RestService should be available");
        restService.initialize();
        Context.getAdministrationService().saveGlobalProperty(
            new GlobalProperty(RestConstants.SWAGGER_QUIET_DOCS_GLOBAL_PROPERTY_NAME, "true"));
        Context.flushSession();
        log.info("=== Setup Complete ===");
    }

    @Test
    @DisplayName("Generate OpenAPI 3.0 spec for all resources with oneOf representations")
    public void generateOpenApiSpecForAllResources() throws Exception {
        RestService restService = Context.getService(RestService.class);
        Collection<DelegatingResourceHandler<?>> handlers = restService.getResourceHandlers();
        assertTrue(handlers.size() > 0, "Should have at least one resource handler");

        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        ObjectNode openApiSpec = createBaseOpenApiStructure(mapper);
        ObjectNode schemas = openApiSpec.with("components").with("schemas");
        ObjectNode paths = mapper.createObjectNode();

        // For each resource handler
        for (DelegatingResourceHandler<?> handler : handlers) {
            Resource annotation = handler.getClass().getAnnotation(Resource.class);
            if (annotation == null) continue;
            String resourceName = annotation.name(); // e.g., v1/patient
            String resourcePath = "/ws/rest/" + resourceName + "/{uuid}";
            Class<?> delegateType = getDelegateTypeFromResource(handler);

            // For each of the three standard representations
            List<String> repNames = Arrays.asList("default", "full", "ref");
            Map<String, String> repToSchemaRef = new LinkedHashMap<>();
            for (String rep : repNames) {
                Representation representation = getRepresentationByName(rep);
                String schemaName = capitalize(resourceName.replaceAll(".*/", "")) + capitalize(rep); // e.g., PatientDefault
                ObjectNode schema = generateRepresentationSchemaRecursive(mapper, handler, representation, delegateType, schemaName, schemas, 2, new HashSet<>());
                if (schema != null) {
                    schemas.set(schemaName, schema);
                    String ref = "#/components/schemas/" + schemaName;
                    repToSchemaRef.put(rep, ref);
                }
            }
            if (repToSchemaRef.isEmpty()) continue; // No valid reps, skip

            // Build the GET-by-UUID path
            ObjectNode resourcePathNode = mapper.createObjectNode();
            ObjectNode getOp = mapper.createObjectNode();
            getOp.put("summary", "Get a " + resourceName.replaceAll(".*/", "") + " by UUID");
            ArrayNode parameters = mapper.createArrayNode();
            // Path parameter: uuid
            ObjectNode uuidParam = mapper.createObjectNode();
            uuidParam.put("name", "uuid");
            uuidParam.put("in", "path");
            uuidParam.put("required", true);
            ObjectNode uuidSchema = mapper.createObjectNode();
            uuidSchema.put("type", "string");
            uuidParam.set("schema", uuidSchema);
            uuidParam.put("description", "The UUID of the resource");
            parameters.add(uuidParam);
            // Query parameter: v
            ObjectNode vParam = mapper.createObjectNode();
            vParam.put("name", "v");
            vParam.put("in", "query");
            vParam.put("required", false);
            vParam.put("description", "The representation to return. Allowed values: 'default', 'full', 'ref'");
            ObjectNode vSchema = mapper.createObjectNode();
            vSchema.put("type", "string");
            ArrayNode vEnum = mapper.createArrayNode();
            vEnum.add("default"); vEnum.add("full"); vEnum.add("ref");
            vSchema.set("enum", vEnum);
            vParam.set("schema", vSchema);
            parameters.add(vParam);
            getOp.set("parameters", parameters);
            // Responses
            ObjectNode responses = mapper.createObjectNode();
            ObjectNode resp200 = mapper.createObjectNode();
            resp200.put("description", capitalize(resourceName.replaceAll(".*/", "")) + " resource in the requested representation");
            ObjectNode content = mapper.createObjectNode();
            ObjectNode appJson = mapper.createObjectNode();
            ObjectNode schema = mapper.createObjectNode();
            ArrayNode oneOf = mapper.createArrayNode();
            for (String ref : repToSchemaRef.values()) {
                ObjectNode refNode = mapper.createObjectNode();
                refNode.put("$ref", ref);
                oneOf.add(refNode);
            }
            schema.set("oneOf", oneOf);
            // Discriminator for v (only for present reps)
            ObjectNode discriminator = mapper.createObjectNode();
            discriminator.put("propertyName", "v");
            ObjectNode mapping = mapper.createObjectNode();
            for (Map.Entry<String, String> entry : repToSchemaRef.entrySet()) {
                mapping.put(entry.getKey(), entry.getValue());
            }
            discriminator.set("mapping", mapping);
            schema.set("discriminator", discriminator);
            appJson.set("schema", schema);
            content.set("application/json", appJson);
            resp200.set("content", content);
            responses.set("200", resp200);
            getOp.set("responses", responses);
            resourcePathNode.set("get", getOp);
            paths.set(resourcePath, resourcePathNode);
        }
        openApiSpec.set("paths", paths);
        writeJsonToFile(mapper, openApiSpec, OUTPUT_FILE);
        log.info("\u2713 OpenAPI 3.0 spec for all resources generated: {}", OUTPUT_FILE);
    }

    private Representation getRepresentationByName(String rep) {
        switch (rep.toLowerCase()) {
            case "default": return Representation.DEFAULT;
            case "full": return Representation.FULL;
            case "ref": return Representation.REF;
            default: return null;
        }
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }

    // Enhanced handler lookup: match by simple name, full class name, and ignore generics
    private DelegatingResourceHandler<?> findHandlerForType(String javaType) {
        RestService restService = Context.getService(RestService.class);
        String simpleType = javaType.replaceAll("<.*>", "").trim(); // Remove generics
        for (DelegatingResourceHandler<?> handler : restService.getResourceHandlers()) {
            Resource annotation = handler.getClass().getAnnotation(Resource.class);
            if (annotation != null) {
                String supportedSimple = annotation.supportedClass().getSimpleName();
                String supportedFull = annotation.supportedClass().getName();
                if (supportedSimple.equals(simpleType) || supportedFull.equals(javaType)) {
                    return handler;
                }
            }
        }
        return null;
    }

    // Recursively generate schemas for referenced types, with a depth limit
    private ObjectNode generateRepresentationSchemaRecursive(ObjectMapper mapper, DelegatingResourceHandler<?> handler, Representation representation, Class<?> delegateType, String schemaName, ObjectNode schemas, int depth, Set<String> seen) {
        if (depth < 0) return null;
        try {
            DelegatingResourceDescription description = handler.getRepresentationDescription(representation);
            if (description == null || description.getProperties() == null) {
                return null;
            }
            ObjectNode schema = mapper.createObjectNode();
            schema.put("type", "object");
            schema.put("description", schemaName + " representation");
            ObjectNode properties = mapper.createObjectNode();
            for (Map.Entry<String, DelegatingResourceDescription.Property> entry : description.getProperties().entrySet()) {
                String propertyName = entry.getKey();
                String javaType = detectPropertyType(delegateType, propertyName, entry.getValue());
                String openApiType = mapJavaTypeToOpenApiType(javaType);
                // Handle collections: Set<...>, List<...>, Collection<...>
                if (isCollectionType(javaType)) {
                    String itemType = extractGenericType(javaType);
                    ObjectNode arraySchema = mapper.createObjectNode();
                    arraySchema.put("type", "array");
                    ObjectNode items = mapper.createObjectNode();
                    if (isOpenMRSDomainType(itemType)) {
                        String refName = capitalize(itemType);
                        if (!schemas.has(refName)) {
                            DelegatingResourceHandler<?> refHandler = findHandlerForType(itemType);
                            if (refHandler != null) {
                                ObjectNode refSchema = generateRepresentationSchemaRecursive(mapper, refHandler, Representation.DEFAULT, getDelegateTypeFromResource(refHandler), refName, schemas, depth - 1, seen);
                                if (refSchema != null) {
                                    schemas.set(refName, refSchema);
                                }
                            } else {
                                log.warn("No handler found for type '{}', using minimal schema.", itemType);
                                ObjectNode fallback = createMinimalDomainSchema(mapper, itemType);
                                schemas.set(refName, fallback);
                            }
                        }
                        items.put("$ref", "#/components/schemas/" + refName);
                    } else {
                        items.put("type", mapJavaTypeToOpenApiType(itemType));
                        if (itemType.equals("Date") || itemType.contains("Date")) {
                            items.put("format", "date-time");
                        }
                    }
                    arraySchema.set("items", items);
                    arraySchema.put("description", "Array of " + itemType);
                    properties.set(propertyName, arraySchema);
                } else if (isOpenMRSDomainType(javaType) && !seen.contains(javaType)) {
                    seen.add(javaType);
                    String refName = capitalize(javaType);
                    if (!schemas.has(refName)) {
                        DelegatingResourceHandler<?> refHandler = findHandlerForType(javaType);
                        if (refHandler != null) {
                            ObjectNode refSchema = generateRepresentationSchemaRecursive(mapper, refHandler, Representation.DEFAULT, getDelegateTypeFromResource(refHandler), refName, schemas, depth - 1, seen);
                            if (refSchema != null) {
                                schemas.set(refName, refSchema);
                            }
                        } else {
                            log.warn("No handler found for type '{}', using minimal schema.", javaType);
                            ObjectNode fallback = createMinimalDomainSchema(mapper, javaType);
                            schemas.set(refName, fallback);
                        }
                    }
                    ObjectNode propSchema = mapper.createObjectNode();
                    propSchema.put("$ref", "#/components/schemas/" + refName);
                    properties.set(propertyName, propSchema);
                } else {
                    ObjectNode propSchema = createPropertySchema(mapper, propertyName, javaType, openApiType, schemas);
                    properties.set(propertyName, propSchema);
                }
            }
            schema.set("properties", properties);
            return schema;
        } catch (Exception e) {
            log.warn("Could not generate schema for {} {}: {}", schemaName, representation, e.getMessage());
            return null;
        }
    }

    // Create a minimal schema for a domain type
    private ObjectNode createMinimalDomainSchema(ObjectMapper mapper, String javaType) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        schema.put("description", "Minimal schema for " + javaType);
        ObjectNode properties = mapper.createObjectNode();
        ObjectNode idProperty = mapper.createObjectNode();
        idProperty.put("type", "integer");
        idProperty.put("description", "Unique identifier");
        properties.set("id", idProperty);
        ObjectNode uuidProperty = mapper.createObjectNode();
        uuidProperty.put("type", "string");
        uuidProperty.put("description", "Universally unique identifier");
        properties.set("uuid", uuidProperty);
        ObjectNode displayProperty = mapper.createObjectNode();
        displayProperty.put("type", "string");
        displayProperty.put("description", "Display representation");
        properties.set("display", displayProperty);
        schema.set("properties", properties);
        return schema;
    }

    private ObjectNode createBaseOpenApiStructure(ObjectMapper mapper) {
        ObjectNode openApiSpec = mapper.createObjectNode();
        openApiSpec.put("openapi", "3.0.1");
        ObjectNode info = mapper.createObjectNode();
        info.put("title", "OpenMRS REST API");
        info.put("version", detectOpenmrsVersion());
        info.put("description", "Generated OpenAPI specification for OpenMRS REST resources");
        openApiSpec.set("info", info);
        ObjectNode components = mapper.createObjectNode();
        ObjectNode schemas = mapper.createObjectNode();
        components.set("schemas", schemas);
        openApiSpec.set("components", components);
        return openApiSpec;
    }

    private String detectOpenmrsVersion() {
        try {
            String version = Context.getAdministrationService().getGlobalProperty("openmrs.version");
            if (version != null && !version.isEmpty()) {
                return version;
            }
        } catch (Exception ignored) {}
        return "unknown";
    }

    private ObjectNode analyzeResource(ObjectMapper mapper, DelegatingResourceHandler<?> handler, ObjectNode schemas) {
        ObjectNode resourceNode = mapper.createObjectNode();
        Resource annotation = handler.getClass().getAnnotation(Resource.class);
        String resourceName = annotation.name();
        Class<?> delegateType = getDelegateTypeFromResource(handler);
        resourceNode.put("resourceName", resourceName);
        resourceNode.put("supportedClass", annotation.supportedClass().getSimpleName());
        if (delegateType != null) {
            resourceNode.put("delegateType", delegateType.getSimpleName());
        }
        ObjectNode representations = mapper.createObjectNode();
        // (No longer needed: analyzeRepresentation with extra String argument)
        resourceNode.set("representations", representations);
        return resourceNode;
    }

    private String detectPropertyType(Class<?> delegateType, String propertyName, DelegatingResourceDescription.Property property) {
        // Try to get type from delegate class
        if (delegateType != null) {
            String typeFromClass = getPropertyTypeFromClass(delegateType, propertyName);
            if (typeFromClass != null) {
                return typeFromClass;
            }
        }
        // Fallback to property convertAs
        if (property.getConvertAs() != null) {
            return property.getConvertAs().getSimpleName();
        }
        // Fallback to method return type
        if (property.getMethod() != null) {
            return getTypeName(property.getMethod().getGenericReturnType());
        }
        // Last resort: heuristics
        return guessJavaTypeFromPropertyName(propertyName);
    }

    private String getPropertyTypeFromClass(Class<?> clazz, String propertyName) {
        try {
            PropertyDescriptor[] descriptors = Introspector.getBeanInfo(clazz).getPropertyDescriptors();
            for (PropertyDescriptor descriptor : descriptors) {
                if (propertyName.equals(descriptor.getName()) && descriptor.getReadMethod() != null) {
                    return getTypeName(descriptor.getReadMethod().getGenericReturnType());
                }
            }
            Field field = findField(clazz, propertyName);
            if (field != null) {
                return getTypeName(field.getGenericType());
            }
        } catch (IntrospectionException ignored) {}
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
            } catch (NoSuchFieldException ignored) {}
            current = current.getSuperclass();
        }
        return null;
    }

    private String getTypeName(Type type) {
        if (type instanceof Class) {
            return ((Class<?>) type).getSimpleName();
        } else if (type instanceof ParameterizedType) {
            ParameterizedType pt = (ParameterizedType) type;
            Type rawType = pt.getRawType();
            Type[] args = pt.getActualTypeArguments();
            if (rawType instanceof Class) {
                String rawTypeName = ((Class<?>) rawType).getSimpleName();
                if (args.length > 0) {
                    List<String> argNames = new ArrayList<>();
                    for (Type arg : args) {
                        argNames.add(getTypeName(arg));
                    }
                    return rawTypeName + "<" + String.join(", ", argNames) + ">";
                }
                return rawTypeName;
            }
        }
        return type.toString();
    }

    private String guessJavaTypeFromPropertyName(String propertyName) {
        if (propertyName == null) return "String";
        String lower = propertyName.toLowerCase();
        if (lower.contains("date") || lower.contains("time")) return "Date";
        if (lower.startsWith("is") || lower.equals("voided") || lower.equals("retired")) return "Boolean";
        if (lower.contains("id") && !lower.equals("uuid")) return "Integer";
        if (lower.endsWith("s") && !lower.equals("address")) return "List<String>";
        return "String";
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

    private ObjectNode createPropertySchema(ObjectMapper mapper, String propName, String javaType, String openApiType, ObjectNode schemas) {
        ObjectNode propSchema = mapper.createObjectNode();
        // Handle OpenMRS domain types with $ref
        if (isOpenMRSDomainType(javaType)) {
            String refName = cleanTypeNameForRef(javaType);
            propSchema.put("$ref", "#/components/schemas/" + refName);
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
        if (javaType.equals("Date") || javaType.contains("Date")) {
            propSchema.put("format", "date-time");
        }
        propSchema.put("description", propName + " (" + javaType + ")");
        return propSchema;
    }

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

    private String cleanTypeNameForRef(String javaType) {
        return javaType.replaceAll("[<>]", "").replace("[]", "Array");
    }

    private void createReferencedTypeSchema(ObjectMapper mapper, ObjectNode schemas, String refName, String javaType) {
        ObjectNode typeSchema = mapper.createObjectNode();
        typeSchema.put("type", "object");
        typeSchema.put("description", "Schema for " + refName + " (Java type: " + javaType + ")");
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

    // Utility: Check if a type string is a collection
    private boolean isCollectionType(String javaType) {
        return javaType.startsWith("Set<") || javaType.startsWith("List<") || javaType.startsWith("Collection<");
    }

    // Utility: Extract the generic type from a parameterized type string
    private String extractGenericType(String parameterizedType) {
        if (parameterizedType.contains("<") && parameterizedType.contains(">")) {
            int start = parameterizedType.indexOf("<") + 1;
            int end = parameterizedType.lastIndexOf(">");
            String genericType = parameterizedType.substring(start, end);
            if (genericType.contains(",")) {
                genericType = genericType.split(",")[0].trim();
            }
            return genericType;
        }
        return "string";
    }

    private void writeJsonToFile(ObjectMapper mapper, ObjectNode jsonNode, String filename) {
        try {
            File outputDir = new File("target");
            if (!outputDir.exists()) {
                outputDir.mkdirs();
            }
            File outputFile = new File(outputDir, filename);
            mapper.writeValue(outputFile, jsonNode);
            log.info("\u2713 Saved: {}", outputFile.getAbsolutePath());
        } catch (Exception e) {
            log.error("Failed to write {}: {}", filename, e.getMessage());
        }
    }

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
} 