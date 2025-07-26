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
import org.openmrs.plugin.rest.analyzer.introspection.SchemaIntrospectionService;
import org.openmrs.plugin.rest.analyzer.introspection.SchemaIntrospectionServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.BooleanSchema;
import io.swagger.v3.oas.models.media.NumberSchema;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;

import java.io.File;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Enhanced OpenAPI 3.0 specification generator for OpenMRS REST resources.
 * Uses SchemaIntrospectionService for accurate property type discovery and Swagger-Core models.
 */
@DisplayName("OpenMRS OpenAPI Spec Generator Test")
public class OpenmrsOpenapiSpecGeneratorTest extends BaseModuleWebContextSensitiveTest {
    
    private static final Logger log = LoggerFactory.getLogger(OpenmrsOpenapiSpecGeneratorTest.class);
    private static final String OUTPUT_FILE = "openapi-spec-output.json";
    
    private SchemaIntrospectionService schemaIntrospectionService;

    @BeforeEach
    public void setup() throws Exception {
        log.info("=== Setting up OpenAPI Spec Generator V2 Test ===");
        
        RestService restService = Context.getService(RestService.class);
        assertNotNull(restService, "RestService should be available");
        restService.initialize();
        
        Context.getAdministrationService().saveGlobalProperty(
            new GlobalProperty(RestConstants.SWAGGER_QUIET_DOCS_GLOBAL_PROPERTY_NAME, "true"));
        Context.flushSession();
        
        schemaIntrospectionService = new SchemaIntrospectionServiceImpl();
        log.info("=== Setup Complete ===");
    }

    @Test
    @DisplayName("Generate OpenAPI 3.0 spec for all resources using introspection service")
    public void generateOpenApiSpecForAllResources() throws Exception {
        log.info("=== Starting OpenAPI Spec Generation V2 ===");
        
        RestService restService = Context.getService(RestService.class);
        Collection<DelegatingResourceHandler<?>> handlers = restService.getResourceHandlers();
        assertTrue(handlers.size() > 0, "Should have at least one resource handler");
        
        log.info("Found {} REST resource handlers", handlers.size());
        
        OpenAPI openAPI = createBaseOpenApiStructure();
        Components components = new Components();
        Paths paths = new Paths();
        
        for (DelegatingResourceHandler<?> handler : handlers) {
            processResourceHandler(handler, components, paths);
        }
        
        openAPI.setComponents(components);
        openAPI.setPaths(paths);
        
        writeOpenApiToFile(openAPI);
        
        log.info("OpenAPI 3.0 spec generated successfully: {}", OUTPUT_FILE);
    }
    
    private void processResourceHandler(DelegatingResourceHandler<?> handler, Components components, Paths paths) {
        try {
            Resource annotation = handler.getClass().getAnnotation(Resource.class);
            if (annotation == null) {
                log.debug("Skipping handler without @Resource annotation: {}", handler.getClass().getSimpleName());
                return;
            }
            
            String resourceName = annotation.name();
            String resourcePath = "/ws/rest/" + resourceName + "/{uuid}";
            String resourceType = resourceName.replaceAll(".*/", "");
            
            log.info("Processing resource: {} ({})", resourceName, handler.getClass().getSimpleName());
            
            Class<?> delegateType = schemaIntrospectionService.getDelegateType((org.openmrs.module.webservices.rest.web.resource.api.Resource) handler);
            if (delegateType == null) {
                log.warn("Could not determine delegate type for {}", handler.getClass().getSimpleName());
                return;
            }
            
            Map<String, String> allProperties = schemaIntrospectionService.discoverResourceProperties((org.openmrs.module.webservices.rest.web.resource.api.Resource) handler);
            log.debug("Discovered {} properties for {}", allProperties.size(), resourceType);
            
            Map<String, Schema<?>> representationSchemas = new LinkedHashMap<>();
            List<String> representations = Arrays.asList("default", "full", "ref");
            
            for (String repName : representations) {
                Representation representation = getRepresentationByName(repName);
                if (representation == null) continue;
                
                Schema<?> schema = generateRepresentationSchema(handler, representation, allProperties, components);
                if (schema != null) {
                    String schemaName = capitalize(resourceType) + capitalize(repName); // e.g., "PatientDefault"
                    components.addSchemas(schemaName, schema);
                    representationSchemas.put(repName, schema);
                    log.debug("Created schema for {} representation: {}", repName, schemaName);
                }
            }
            
            if (representationSchemas.isEmpty()) {
                log.warn("No valid representations found for {}", resourceName);
                return;
            }
            
            PathItem pathItem = createPathItem(resourceType, representationSchemas);
            paths.addPathItem(resourcePath, pathItem);
            
            log.info("Created path for {} with {} representations", resourceName, representationSchemas.size());
            
        } catch (Exception e) {
            log.error("Error processing resource handler {}: {}", handler.getClass().getSimpleName(), e.getMessage(), e);
        }
    }
    
    private Schema<?> generateRepresentationSchema(DelegatingResourceHandler<?> handler, 
                                                   Representation representation, 
                                                   Map<String, String> allProperties, 
                                                   Components components) {
        try {
            DelegatingResourceDescription description = handler.getRepresentationDescription(representation);
            if (description == null || description.getProperties() == null) {
                log.debug("No description or properties for representation: {}", representation);
                return null;
            }
            
            ObjectSchema schema = new ObjectSchema();
            
            Map<String, Schema> schemaProperties = new HashMap<>();
            for (Map.Entry<String, DelegatingResourceDescription.Property> entry : description.getProperties().entrySet()) {
                String propertyName = entry.getKey();
                String javaType = allProperties.get(propertyName);
                
                if (javaType == null) {
                    log.warn("Property '{}' not found in introspection results, using fallback", propertyName);
                    javaType = "String"; // Fallback
                }
                
                Schema<?> propertySchema = mapToSwaggerSchema(javaType, components);
                schemaProperties.put(propertyName, propertySchema);
            }
            schema.setProperties(schemaProperties);
            
            return schema;
            
        } catch (Exception e) {
            log.warn("Could not generate schema for representation {}: {}", representation, e.getMessage());
            return null;
        }
    }
    
    private Schema<?> mapToSwaggerSchema(String javaType, Components components) {
        if (javaType == null) return new StringSchema();
        
        if (isCollectionType(javaType)) {
            String itemType = extractGenericType(javaType);
            Schema<?> itemSchema = mapToSwaggerSchema(itemType, components);
            return new ArraySchema().items(itemSchema);
        }
        
        String lowerType = javaType.toLowerCase();
        if (lowerType.contains("string") || lowerType.equals("string")) {
            return new StringSchema();
        } else if (lowerType.contains("int") || lowerType.contains("long") || lowerType.equals("integer")) {
            return new IntegerSchema();
        } else if (lowerType.contains("double") || lowerType.contains("float") || lowerType.equals("number")) {
            return new NumberSchema();
        } else if (lowerType.contains("boolean") || lowerType.equals("boolean")) {
            return new BooleanSchema();
        } else if (lowerType.contains("date") || lowerType.equals("date")) {
            return new StringSchema().format("date-time");
        } else if (isOpenMRSDomainType(javaType)) {
            String refName = capitalize(javaType);
            return new Schema<>().$ref("#/components/schemas/" + refName);
        } else {
            return new ObjectSchema();
        }
    }
    
    private PathItem createPathItem(String resourceType, Map<String, Schema<?>> representationSchemas) {
        PathItem pathItem = new PathItem();
        
        Operation getOperation = new Operation();
        getOperation.setSummary("Get a " + resourceType + " by UUID");
        getOperation.setDescription("Retrieve a " + resourceType + " resource in the requested representation");
        
        List<Parameter> parameters = new ArrayList<>();
        
        Parameter uuidParam = new Parameter();
        uuidParam.setName("uuid");
        uuidParam.setIn("path");
        uuidParam.setRequired(true);
        uuidParam.setDescription("The UUID of the " + resourceType);
        uuidParam.setSchema(new StringSchema());
        parameters.add(uuidParam);
        
        Parameter vParam = new Parameter();
        vParam.setName("v");
        vParam.setIn("query");
        vParam.setRequired(false);
        vParam.setDescription("The representation to return. Allowed values: 'default', 'full', 'ref'");
        
        StringSchema vSchema = new StringSchema();
        vSchema.addEnumItem("default");
        vSchema.addEnumItem("full");
        vSchema.addEnumItem("ref");
        vParam.setSchema(vSchema);
        parameters.add(vParam);
        
        getOperation.setParameters(parameters);
        
        ApiResponses responses = new ApiResponses();
        
        ApiResponse response200 = new ApiResponse();
        response200.setDescription("Successful response");
        
        Content content = new Content();
        MediaType mediaType = new MediaType();
        
        ObjectSchema responseSchema = new ObjectSchema();
        List<Schema> oneOfSchemas = new ArrayList<>();
        Map<String, String> mapping = new LinkedHashMap<>();
        for (Map.Entry<String, Schema<?>> entry : representationSchemas.entrySet()) {
            String repName = entry.getKey();
            String schemaName = capitalize(resourceType) + capitalize(repName);
            Schema<?> refSchema = new Schema<>().$ref("#/components/schemas/" + schemaName);
            oneOfSchemas.add(refSchema);
            mapping.put(repName, "#/components/schemas/" + schemaName);
        }
        responseSchema.setOneOf(oneOfSchemas);

        io.swagger.v3.oas.models.media.Discriminator discriminator = new io.swagger.v3.oas.models.media.Discriminator();
        discriminator.setPropertyName("v");
        discriminator.setMapping(mapping);
        responseSchema.setDiscriminator(discriminator);
        mediaType.setSchema(responseSchema);
        content.addMediaType("application/json", mediaType);
        response200.setContent(content);
        
        responses.addApiResponse("200", response200);
        getOperation.setResponses(responses);
        
        pathItem.setGet(getOperation);
        return pathItem;
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
    
    private boolean isCollectionType(String javaType) {
        return javaType.startsWith("List<") || javaType.startsWith("Set<") || javaType.startsWith("Collection<");
    }
    
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
        return "String";
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
    
    private OpenAPI createBaseOpenApiStructure() {
        OpenAPI openAPI = new OpenAPI();
        
        Info info = new Info();
        info.setTitle("OpenMRS REST API");
        info.setVersion(detectOpenmrsVersion());
        info.setDescription("Generated OpenAPI 3.0 specification for OpenMRS REST resources using introspection service");
        
        openAPI.setInfo(info);
        return openAPI;
    }
    
    private String detectOpenmrsVersion() {
        try {
            String version = Context.getAdministrationService().getGlobalProperty("openmrs.version");
            if (version != null && !version.isEmpty()) {
                return version;
            }
        } catch (Exception ignored) {}
        
        String sysProp = System.getProperty("openmrs.version");
        if (sysProp != null && !sysProp.isEmpty()) {
            return sysProp;
        }
        return "2.4.x";
    }
    
    private void writeOpenApiToFile(OpenAPI openAPI) throws Exception {
        File outputDir = new File("target");
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }
        
        File outputFile = new File(outputDir, OUTPUT_FILE);
        
        String json = io.swagger.v3.core.util.Json.pretty(openAPI);
        
        try (java.io.FileWriter writer = new java.io.FileWriter(outputFile)) {
            writer.write(json);
        }
        
        log.info("OpenAPI spec written to: {}", outputFile.getAbsolutePath());
        assertTrue(outputFile.exists(), "Output file should be created");
        assertTrue(outputFile.length() > 0, "Output file should not be empty");
    }
} 