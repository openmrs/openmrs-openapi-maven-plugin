package org.openmrs.plugin.rest.analyzer.test;

import org.junit.Before;
import org.junit.Test;
import org.openmrs.GlobalProperty;
import org.openmrs.api.context.Context;
import org.openmrs.module.webservices.rest.web.RestConstants;
import org.openmrs.module.webservices.rest.web.annotation.Resource;
import org.openmrs.module.webservices.rest.web.api.RestService;
import org.openmrs.module.webservices.rest.web.resource.impl.DelegatingResourceHandler;
import org.openmrs.module.webservices.rest.web.resource.impl.DelegatingResourceDescription;
import org.openmrs.module.webservices.rest.web.representation.Representation;
import org.openmrs.web.test.BaseModuleWebContextSensitiveTest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.io.File;
import java.io.IOException;
import java.util.Collection;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class RepresentationAnalyzerTest extends BaseModuleWebContextSensitiveTest {

    private static final Logger log = LoggerFactory.getLogger(RepresentationAnalyzerTest.class);
    
    private static final String OUTPUT_FILE_NAME = System.getProperty("representation.analyzer.output.file", "representation-analysis.json");
    private static final String OUTPUT_DIR = System.getProperty("representation.analyzer.output.dir", "target");

    @Before
    public void setUp() throws Exception {
        log.info("*******************************************");
        log.info("Setting up OpenMRS context for representation analysis...");
        
        RestService restService = Context.getService(RestService.class);
        log.info("RestService available: " + (restService != null));

        log.info("Initializing REST service...");
        assertNotNull("RestService should be available", restService);
        restService.initialize();
        log.info("REST service initialized");
        log.info("Resource handlers after init: " + restService.getResourceHandlers().size());
        
        Context.getAdministrationService().saveGlobalProperty(
            new GlobalProperty(RestConstants.SWAGGER_QUIET_DOCS_GLOBAL_PROPERTY_NAME, "true"));
        
        Context.flushSession();
        log.info("*******************************************");
    }

    @Test
    public void analyzeResourceRepresentations() throws IOException {
        log.info("=== Starting Representation Analysis ===");
        
        try {
            assertTrue("OpenMRS session should be open", Context.isSessionOpen());
            
            RestService restService = Context.getService(RestService.class);
            assertNotNull("RestService should be available", restService);
            assertNotNull("Resource handlers should be available", restService.getResourceHandlers());
            
            Collection<DelegatingResourceHandler<?>> handlers = restService.getResourceHandlers();
            int handlerCount = handlers.size();
            log.info("Resource handlers found: " + handlerCount);
            
            if (handlerCount == 0) {
                log.warn("No resource handlers found. This may indicate a configuration issue.");
                log.warn("Available services: {}", Context.getRegisteredComponents(Object.class).size());
            }
            
            assertFalse("Should have at least one resource handler", handlers.isEmpty());
            
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode jsonOutput = mapper.createObjectNode();
            
            ObjectNode metadata = createMetadata(mapper, handlerCount);
            jsonOutput.set("metadata", metadata);
            
            ArrayNode resourcesArray = mapper.createArrayNode();
            for (DelegatingResourceHandler<?> handler : handlers) {
                ObjectNode resourceNode = analyzeResourceHandlerAsJson(mapper, handler);
                resourcesArray.add(resourceNode);
            }
            
            jsonOutput.put("resourceCount", handlerCount);
            jsonOutput.set("resources", resourcesArray);
            
            writeJsonToFile(mapper, jsonOutput, handlerCount);
            
            File outputFile = new File(new File(OUTPUT_DIR), OUTPUT_FILE_NAME);
            assertTrue("Output file should exist", outputFile.exists());
            assertTrue("Output file should not be empty", outputFile.length() > 0);
            
        } catch (IllegalStateException e) {
            log.error("OpenMRS context is not properly initialized: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to analyze representations due to context initialization error", e);
            
        } catch (SecurityException e) {
            log.error("Security error accessing REST services: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to analyze representations due to security restrictions", e);
            
        } catch (IOException e) {
            log.error("I/O error during analysis: {}", e.getMessage(), e);
            throw e; 
            
        } catch (RuntimeException e) {
            log.error("Unexpected runtime error during analysis: {}", e.getMessage(), e);
            throw e; 
        }
    }
    
    private ObjectNode createMetadata(ObjectMapper mapper, int handlerCount) {
        ObjectNode metadata = mapper.createObjectNode();
        metadata.put("timestamp", java.time.Instant.now().toString());
        metadata.put("pluginVersion", "1.0.0-SNAPSHOT");
        metadata.put("linksExcluded", true);
        
        ObjectNode config = mapper.createObjectNode();
        config.put("outputFile", OUTPUT_FILE_NAME);
        config.put("outputDir", OUTPUT_DIR);
        metadata.set("configuration", config);
        
        return metadata;
    }
    
    private ObjectNode analyzeResourceHandlerAsJson(ObjectMapper mapper, DelegatingResourceHandler<?> handler) {
        ObjectNode resourceNode = mapper.createObjectNode();
        
        try {
            Resource resourceAnnotation = handler.getClass().getAnnotation(Resource.class);
            
            if (resourceAnnotation != null) {
                resourceNode.put("resourceName", resourceAnnotation.name());
                resourceNode.put("supportedClass", resourceAnnotation.supportedClass().getSimpleName());
                resourceNode.put("handlerClass", handler.getClass().getSimpleName());
                resourceNode.put("handlerPackage", handler.getClass().getPackage().getName());
                
                ArrayNode versionsArray = mapper.createArrayNode();
                for (String version : resourceAnnotation.supportedOpenmrsVersions()) {
                    versionsArray.add(version);
                }
                resourceNode.set("supportedVersions", versionsArray);
                
                ObjectNode representations = analyzeRepresentationsAsJson(mapper, handler);
                resourceNode.set("representations", representations);
                
            } else {
                resourceNode.put("error", "No @Resource annotation found");
                resourceNode.put("className", handler.getClass().getName());
            }
            
        } catch (SecurityException e) {
            log.warn("Security error analyzing handler {}: {}", handler.getClass().getSimpleName(), e.getMessage());
            resourceNode.put("error", e.getMessage());
            resourceNode.put("errorType", "SecurityException");
            resourceNode.put("className", handler.getClass().getName());
            
        } catch (RuntimeException e) {
            log.warn("Unexpected error analyzing handler {}: {}", handler.getClass().getSimpleName(), e.getMessage());
            resourceNode.put("error", e.getMessage());
            resourceNode.put("errorType", e.getClass().getSimpleName());
            resourceNode.put("className", handler.getClass().getName());
        }
        
        return resourceNode;
    }
    
    private ObjectNode analyzeRepresentationsAsJson(ObjectMapper mapper, DelegatingResourceHandler<?> handler) {
        ObjectNode representations = mapper.createObjectNode();
        
        representations.set("DEFAULT", analyzeSpecificRepresentationAsJson(mapper, handler, Representation.DEFAULT));
        representations.set("FULL", analyzeSpecificRepresentationAsJson(mapper, handler, Representation.FULL));
        representations.set("REF", analyzeSpecificRepresentationAsJson(mapper, handler, Representation.REF));
        
        return representations;
    }
    
    private ObjectNode analyzeSpecificRepresentationAsJson(ObjectMapper mapper, DelegatingResourceHandler<?> handler, Representation representation) {
        try {
            DelegatingResourceDescription description = handler.getRepresentationDescription(representation);
            return analyzeRepresentationDescriptionAsJson(mapper, description);
            
        } catch (UnsupportedOperationException e) {
            ObjectNode errorNode = mapper.createObjectNode();
            errorNode.put("error", "Representation not supported");
            errorNode.put("errorType", "UnsupportedOperationException");
            return errorNode;
            
        } catch (IllegalArgumentException e) {
            log.debug("Invalid argument for {} representation in {}: {}", 
                    representation.getRepresentation(), handler.getClass().getSimpleName(), e.getMessage());
            ObjectNode errorNode = mapper.createObjectNode();
            errorNode.put("error", e.getMessage());
            errorNode.put("errorType", "IllegalArgumentException");
            return errorNode;
            
        } catch (IllegalStateException e) {
            log.debug("Invalid state for {} representation in {}: {}", 
                    representation.getRepresentation(), handler.getClass().getSimpleName(), e.getMessage());
            ObjectNode errorNode = mapper.createObjectNode();
            errorNode.put("error", e.getMessage());
            errorNode.put("errorType", "IllegalStateException");
            return errorNode;
            
        } catch (RuntimeException e) {
            log.warn("Unexpected error getting {} representation for {}: {}", 
                    representation.getRepresentation(), handler.getClass().getSimpleName(), e.getMessage());
            ObjectNode errorNode = mapper.createObjectNode();
            errorNode.put("error", e.getMessage());
            errorNode.put("errorType", e.getClass().getSimpleName());
            return errorNode;
        }
    }
    
    private ObjectNode analyzeRepresentationDescriptionAsJson(ObjectMapper mapper, DelegatingResourceDescription description) {
        if (description == null) {
            return null;
        }
        
        try {
            int propertyCount = (description.getProperties() != null) ? description.getProperties().size() : 0;
            int linkCount = (description.getLinks() != null) ? description.getLinks().size() : 0;
            
            ObjectNode repNode = mapper.createObjectNode();
            repNode.put("propertyCount", propertyCount);
            repNode.put("linkCount", linkCount);
            
            return repNode;
            
        } catch (IllegalStateException e) {
            log.debug("Invalid state accessing representation description: {}", e.getMessage());
            ObjectNode errorNode = mapper.createObjectNode();
            errorNode.put("error", e.getMessage());
            errorNode.put("errorType", "IllegalStateException");
            return errorNode;
            
        } catch (RuntimeException e) {
            log.warn("Unexpected error analyzing representation description: {}", e.getMessage());
            ObjectNode errorNode = mapper.createObjectNode();
            errorNode.put("error", e.getMessage());
            errorNode.put("errorType", e.getClass().getSimpleName());
            return errorNode;
        }
    }
    
    private void writeJsonToFile(ObjectMapper mapper, ObjectNode jsonOutput, int handlerCount) throws IOException {
        File outputDir = new File(OUTPUT_DIR);
        
        try {
            if (!outputDir.exists()) {
                boolean created = outputDir.mkdirs();
                if (!created) {
                    throw new IOException("Failed to create output directory: " + outputDir.getAbsolutePath());
                }
            }
            
            File outputFile = new File(outputDir, OUTPUT_FILE_NAME);
            
            try {
                mapper.writerWithDefaultPrettyPrinter().writeValue(outputFile, jsonOutput);
                
                log.info("=== Representation Analysis Complete ===");
                log.info("Resources analyzed: {}", handlerCount);
                log.info("Output written to: {}", outputFile.getAbsolutePath());
                log.info("File size: {} bytes", outputFile.length());
                
                logAnalysisSummary(handlerCount);
                
            } catch (SecurityException e) {
                log.error("Permission denied writing to file: {}", outputFile.getAbsolutePath());
                throw new IOException("Permission denied writing analysis file", e);
                
            } catch (IOException e) {
                log.error("I/O error writing analysis file: {}", e.getMessage());
                throw e;
            }
            
        } catch (SecurityException e) {
            log.error("Security error accessing output directory: {}", outputDir.getAbsolutePath());
            throw new IOException("Security restrictions prevent file creation", e);
        }
    }
    
    private void logAnalysisSummary(int handlerCount) {
        log.info("Analysis summary: {} total handlers processed", handlerCount);
        log.info("=========================================");
    }
}
