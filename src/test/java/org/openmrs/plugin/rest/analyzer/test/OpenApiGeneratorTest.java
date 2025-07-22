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

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.media.Schema;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Collection;
import java.util.Map;
import java.io.File;

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
    @DisplayName("Basic OpenAPI 3.0 structure creation")
    public void testBasicOpenAPIStructure() throws Exception {
        log.info("=== Testing Basic OpenAPI 3.0 Structure Creation ===");
        
        OpenAPI openAPI = new OpenAPI();
        openAPI.info(new Info()
            .title("OpenMRS REST API") 
            .version("1.0.0")
            .description("Generated OpenAPI specification for OpenMRS REST resources"));
            
        openAPI.components(new Components());
        
        // Verify basic structure
        assertNotNull(openAPI.getInfo(), "OpenAPI info should not be null");
        assertNotNull(openAPI.getComponents(), "OpenAPI components should not be null");
        assertEquals("OpenMRS REST API", openAPI.getInfo().getTitle());
        
        log.info("✓ Basic OpenAPI 3.0 structure created successfully");
        log.info("  Title: {}", openAPI.getInfo().getTitle());
        log.info("  Version: {}", openAPI.getInfo().getVersion());
        log.info("=== Basic OpenAPI Structure Test Complete ===");
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
                    
                    // Log detailed property analysis
                    logPropertyDetails(propertyName, property);
                    
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
            log.debug("Type from convertAs: {} -> {}", property.getConvertAs().getSimpleName(), type);
            return type;
        }
        
        // Strategy 2: Check representation level
        if (property.getRep() != null) {
            if (property.getRep() == Representation.REF) {
                log.debug("Type from REF representation -> string (reference)");
                return "string"; // REF usually returns uuid/display
            }
            if (property.getRep() == Representation.FULL || property.getRep() == Representation.DEFAULT) {
                log.debug("Type from {} representation -> object", property.getRep().getRepresentation());
                return "object"; // Full nested object
            }
        }
        
        // Strategy 3: Check method return type
        if (property.getMethod() != null) {
            String type = mapJavaTypeToOpenApi(property.getMethod().getReturnType());
            log.debug("Type from method: {} -> {}", property.getMethod().getReturnType().getSimpleName(), type);
            return type;
        }
        
        // Strategy 4: Heuristic based on property name
        String type = guessTypeFromPropertyName(property.getDelegateProperty());
        log.debug("Type from name heuristic: '{}' -> {}", property.getDelegateProperty(), type);
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
    
    private void logPropertyDetails(String propertyName, DelegatingResourceDescription.Property property) {
        log.debug("=== Property Details: {} ===", propertyName);
        log.debug("  Delegate Property: {}", property.getDelegateProperty());
        log.debug("  Method: {}", property.getMethod() != null ? property.getMethod().getName() : "null");
        log.debug("  Representation: {}", property.getRep() != null ? property.getRep().getRepresentation() : "null");
        log.debug("  Convert As: {}", property.getConvertAs() != null ? property.getConvertAs().getSimpleName() : "null");
        log.debug("  Required: {}", property.isRequired());
    }
    
    /**
     * Step 4: Create a simple OpenAPI schema for a single property
     */
    @Test
    @DisplayName("Single property OpenAPI schema generation")
    public void testSinglePropertySchemaGeneration() {
        log.info("=== Testing Single Property OpenAPI Schema Generation ===");
        
        RestService restService = Context.getService(RestService.class);
        Collection<DelegatingResourceHandler<?>> resourceHandlers = restService.getResourceHandlers();
        DelegatingResourceHandler<?> firstResource = resourceHandlers.iterator().next();
        
        Resource resourceAnnotation = firstResource.getClass().getAnnotation(Resource.class);
        String resourceName = resourceAnnotation.name();
        log.info("Creating schema for resource: {}", resourceName);
        
        DelegatingResourceDescription description = firstResource.getRepresentationDescription(Representation.DEFAULT);
        Map<String, DelegatingResourceDescription.Property> properties = description.getProperties();
        
        // Create an OpenAPI schema for the first property as proof of concept
        Map.Entry<String, DelegatingResourceDescription.Property> firstProperty = properties.entrySet().iterator().next();
        String propertyName = firstProperty.getKey();
        DelegatingResourceDescription.Property property = firstProperty.getValue();
        
        Schema<?> propertySchema = createSimplePropertySchema(property);
        
        assertNotNull(propertySchema, "Property schema should not be null");
        assertNotNull(propertySchema.getType(), "Property schema should have a type");
        
        log.info("✓ Generated OpenAPI schema for property '{}'", propertyName);
        log.info("  Type: {}", propertySchema.getType());
        log.info("  Description: {}", propertySchema.getDescription());
        log.info("=== Single Property Schema Generation Test Complete ===");
    }
    
    /**
     * Create a simple OpenAPI schema for a single property using modern methods
     */
    private Schema<?> createSimplePropertySchema(DelegatingResourceDescription.Property property) {
        Schema<Object> schema = new Schema<>();
        
        // Only set essential fields - let Jackson ignore nulls
        String openApiType = detectPropertyType(property);
        schema.setType(openApiType);
        
        // Set a basic description only if we have delegate property info
        if (property.getDelegateProperty() != null) {
            String description = "Property: " + property.getDelegateProperty();
            if (property.isRequired()) {
                description += " (Required)";
            } else {
                description += " (Optional)";
            }
            schema.setDescription(description);
        }
        
        return schema;
    }
    
    /**
     * Create a minimalist clean schema - only essential fields
     */
    private Schema<?> createCleanPropertySchema(DelegatingResourceDescription.Property property) {
        Schema<Object> schema = new Schema<>();
        
        // Only set the absolute essentials
        String openApiType = detectPropertyType(property);
        schema.setType(openApiType);
        
        // Debug logging to see what's happening
        String propertyName = property.getDelegateProperty();
        log.info("Property '{}': detected type '{}', convertAs={}, method={}, rep={}", 
            propertyName, openApiType, 
            property.getConvertAs() != null ? property.getConvertAs().getSimpleName() : "null",
            property.getMethod() != null ? property.getMethod().getReturnType().getSimpleName() : "null",
            property.getRep() != null ? property.getRep().getRepresentation() : "null");
        
        // Add description only if meaningful
        if (propertyName != null) {
            schema.setDescription(propertyName);
        }
        
        // Handle array items
        if ("array".equals(openApiType)) {
            Schema<Object> itemSchema = new Schema<>();
            itemSchema.setType("string"); // Default item type
            schema.setItems(itemSchema);
        }
        
        return schema;
    }
    
    /**
     * Step 5: Generate and save complete OpenAPI spec to file
     */
    @Test
    @DisplayName("Generate complete OpenAPI specification file")
    public void testGenerateCompleteOpenApiSpec() throws Exception {
        log.info("=== Generating Complete OpenAPI Specification ===");
        
        RestService restService = Context.getService(RestService.class);
        Collection<DelegatingResourceHandler<?>> resourceHandlers = restService.getResourceHandlers();
        
        // Create base OpenAPI structure
        OpenAPI openAPI = new OpenAPI();
        openAPI.info(new Info()
            .title("OpenMRS REST API")
            .version("1.0.0")
            .description("Generated OpenAPI specification for OpenMRS REST resources"));
        openAPI.components(new Components());
        
        // Add schemas for first few resources
        int resourceCount = 0;
        for (DelegatingResourceHandler<?> handler : resourceHandlers) {
            if (resourceCount >= 3) break; // Limit for controlled output
            
            Resource resourceAnnotation = handler.getClass().getAnnotation(Resource.class);
            if (resourceAnnotation != null) {
                try {
                    addResourceSchema(openAPI, handler, resourceAnnotation);
                    resourceCount++;
                } catch (Exception e) {
                    log.warn("Skipping resource {}: {}", resourceAnnotation.name(), e.getMessage());
                }
            }
        }
        
        // Save to file
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        // Configure Jackson to ignore null and empty values for clean output
        mapper.setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);
        mapper.setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY);
        
        File outputDir = new File("target");
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }
        
        File outputFile = new File(outputDir, "openapi-spec.json");
        mapper.writeValue(outputFile, openAPI);
        
        log.info("✓ Generated OpenAPI specification with {} resource schemas", resourceCount);
        log.info("✓ Saved to: {}", outputFile.getAbsolutePath());
        log.info("=== OpenAPI Generation Complete ===");
        
        // Verify the file was created
        assertTrue(outputFile.exists(), "OpenAPI spec file should be created");
        assertTrue(outputFile.length() > 0, "OpenAPI spec file should not be empty");
    }
    
    /**
     * Add a resource schema to the OpenAPI specification
     */
    @SuppressWarnings("rawtypes")
    private void addResourceSchema(OpenAPI openAPI, DelegatingResourceHandler<?> handler, Resource resourceAnnotation) {
        String resourceName = resourceAnnotation.name();
        
        try {
            DelegatingResourceDescription description = handler.getRepresentationDescription(Representation.DEFAULT);
            if (description != null && description.getProperties() != null) {
                
                Schema<Object> resourceSchema = new Schema<>();
                resourceSchema.setType("object");
                resourceSchema.setDescription("Schema for " + resourceName + " resource");
                
                // Add properties to schema
                Map<String, Schema> properties = new java.util.HashMap<>();
                for (Map.Entry<String, DelegatingResourceDescription.Property> entry : 
                        description.getProperties().entrySet()) {
                    
                    String propertyName = entry.getKey();
                    DelegatingResourceDescription.Property property = entry.getValue();
                    
                    Schema<?> propertySchema = createCleanPropertySchema(property);
                    properties.put(propertyName, (Schema) propertySchema);
                }
                
                resourceSchema.setProperties(properties);
                
                // Add to OpenAPI components
                if (openAPI.getComponents().getSchemas() == null) {
                    openAPI.getComponents().setSchemas(new java.util.HashMap<>());
                }
                openAPI.getComponents().getSchemas().put(resourceName, resourceSchema);
                
                log.info("Added schema for resource '{}' with {} properties", 
                    resourceName, properties.size());
                
            }
        } catch (UnsupportedOperationException e) {
            throw new RuntimeException("DEFAULT representation not supported for " + resourceName);
        }
    }
    
    /**
     * Step 6: Generate clean, minimal OpenAPI spec manually
     */
    @Test
    @DisplayName("Generate clean minimal OpenAPI specification")
    public void testGenerateCleanOpenApiSpec() throws Exception {
        log.info("=== Generating Clean Minimal OpenAPI Specification ===");
        
        RestService restService = Context.getService(RestService.class);
        Collection<DelegatingResourceHandler<?>> resourceHandlers = restService.getResourceHandlers();
        
        // Create manual clean structure using Jackson ObjectNode
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        
        ObjectNode openApiSpec = mapper.createObjectNode();
        openApiSpec.put("openapi", "3.0.1");
        
        ObjectNode info = mapper.createObjectNode();
        info.put("title", "OpenMRS REST API");
        info.put("version", "1.0.0");
        info.put("description", "Generated OpenAPI specification for OpenMRS REST resources");
        openApiSpec.set("info", info);
        
        ObjectNode components = mapper.createObjectNode();
        ObjectNode schemas = mapper.createObjectNode();
        
        // Add schemas for first few resources
        int resourceCount = 0;
        for (DelegatingResourceHandler<?> handler : resourceHandlers) {
            if (resourceCount >= 3) break;
            
            Resource resourceAnnotation = handler.getClass().getAnnotation(Resource.class);
            if (resourceAnnotation != null) {
                try {
                    addCleanResourceSchema(schemas, handler, resourceAnnotation, mapper);
                    resourceCount++;
                } catch (Exception e) {
                    log.warn("Skipping resource {}: {}", resourceAnnotation.name(), e.getMessage());
                }
            }
        }
        
        components.set("schemas", schemas);
        openApiSpec.set("components", components);
        
        // Save clean output
        File outputDir = new File("target");
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }
        
        File outputFile = new File(outputDir, "clean-openapi-spec.json");
        mapper.writeValue(outputFile, openApiSpec);
        
        log.info("✓ Generated CLEAN OpenAPI specification with {} resource schemas", resourceCount);
        log.info("✓ Saved to: {}", outputFile.getAbsolutePath());
        
        assertTrue(outputFile.exists(), "Clean OpenAPI spec file should be created");
        assertTrue(outputFile.length() > 0, "Clean OpenAPI spec file should not be empty");
    }
    
    /**
     * Add a clean resource schema using manual ObjectNode construction
     */
    private void addCleanResourceSchema(ObjectNode schemas, DelegatingResourceHandler<?> handler, 
            Resource resourceAnnotation, ObjectMapper mapper) {
        String resourceName = resourceAnnotation.name();
        
        try {
            DelegatingResourceDescription description = handler.getRepresentationDescription(Representation.DEFAULT);
            if (description != null && description.getProperties() != null) {
                
                ObjectNode resourceSchema = mapper.createObjectNode();
                resourceSchema.put("type", "object");
                resourceSchema.put("description", "Schema for " + resourceName + " resource");
                
                ObjectNode properties = mapper.createObjectNode();
                
                for (Map.Entry<String, DelegatingResourceDescription.Property> entry : 
                        description.getProperties().entrySet()) {
                    
                    String propertyName = entry.getKey();
                    DelegatingResourceDescription.Property property = entry.getValue();
                    
                    ObjectNode propertySchema = mapper.createObjectNode();
                    
                    // Detect clean type
                    String cleanType = detectCleanPropertyType(property);
                    propertySchema.put("type", cleanType);
                    
                    // Add simple description 
                    if (property.getDelegateProperty() != null) {
                        propertySchema.put("description", property.getDelegateProperty());
                    }
                    
                    // Handle arrays
                    if ("array".equals(cleanType)) {
                        ObjectNode items = mapper.createObjectNode();
                        items.put("type", "string"); // Default item type
                        propertySchema.set("items", items);
                    }
                    
                    properties.set(propertyName, propertySchema);
                }
                
                resourceSchema.set("properties", properties);
                schemas.set(resourceName, resourceSchema);
                
            }
        } catch (UnsupportedOperationException e) {
            throw new RuntimeException("DEFAULT representation not supported for " + resourceName);
        }
    }
    
    /**
     * Simpler type detection with better logic
     */
    private String detectCleanPropertyType(DelegatingResourceDescription.Property property) {
        // Strategy 1: Check method return type first (most reliable)
        if (property.getMethod() != null) {
            Class<?> returnType = property.getMethod().getReturnType();
            String type = mapJavaTypeToOpenApi(returnType);
            if (!"object".equals(type)) { // If we got a specific type, use it
                return type;
            }
        }
        
        // Strategy 2: Check convertAs class
        if (property.getConvertAs() != null) {
            String type = mapJavaTypeToOpenApi(property.getConvertAs());
            if (!"object".equals(type)) {
                return type;
            }
        }
        
        // Strategy 3: Check representation level
        if (property.getRep() != null) {
            if (property.getRep() == Representation.REF) {
                return "string"; // REF typically returns uuid + display
            }
        }
        
        // Strategy 4: Property name heuristics
        String type = guessTypeFromPropertyName(property.getDelegateProperty());
        return type;
    }
}
