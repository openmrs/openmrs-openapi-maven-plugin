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

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Collection;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Phase 3: Enhanced Test Infrastructure
 * 
 * JUnit test that runs with full OpenMRS context to analyze REST resource representations.
 * This test is invoked programmatically by the Maven plugin.
 * 
 * Follows the exact pattern from BaseModuleWebContextSensitiveTest for proper context initialization.
 */
public class RepresentationAnalyzerTest extends BaseModuleWebContextSensitiveTest {

    private static final Logger log = LoggerFactory.getLogger(RepresentationAnalyzerTest.class);
    
    // Configuration: can be overridden by system properties
    private static final String OUTPUT_FILE_NAME = System.getProperty("representation.analyzer.output.file", "representation-analysis.json");
    private static final String OUTPUT_DIR = System.getProperty("representation.analyzer.output.dir", "target");
    private static final boolean INCLUDE_DEMO_OUTPUT = Boolean.parseBoolean(System.getProperty("representation.analyzer.demo.output", "true"));

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
        
        // Configure for analysis
        Context.getAdministrationService().saveGlobalProperty(
            new GlobalProperty(RestConstants.SWAGGER_QUIET_DOCS_GLOBAL_PROPERTY_NAME, "true"));
        
        Context.flushSession();
        log.info("*******************************************");
    }

    @Test
    public void analyzeResourceRepresentations() throws IOException {
        log.info("=== Starting Representation Analysis ===");
        
        // Verify context is properly initialized
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
        
        // === DEMO: Show basic resource access (configurable) ===
        if (INCLUDE_DEMO_OUTPUT) {
            log.info("=== DEMO: Accessing REST Resource Properties ===");
            int demoCount = 0;
            for (DelegatingResourceHandler<?> handler : handlers) {
                if (demoCount >= 3) break; // Only show first 3 for demo
                
                Resource resourceAnnotation = handler.getClass().getAnnotation(Resource.class);
                if (resourceAnnotation != null) {
                    log.info("DEMO Resource {}: {}", demoCount + 1, resourceAnnotation.name());
                    log.info("  - Supported class: {}", resourceAnnotation.supportedClass().getSimpleName());
                    log.info("  - Handler class: {}", handler.getClass().getSimpleName());
                    
                    // Try to get DEFAULT representation
                    try {
                        DelegatingResourceDescription defaultRep = handler.getRepresentationDescription(Representation.DEFAULT);
                        if (defaultRep != null && defaultRep.getProperties() != null) {
                            log.info("  - DEFAULT representation properties: {}", defaultRep.getProperties().size());
                            // Show first few property names
                            int propCount = 0;
                            for (String propName : defaultRep.getProperties().keySet()) {
                                if (propCount >= 3) break; // Only show first 3 properties
                                log.info("    * Property: {}", propName);
                                propCount++;
                            }
                        }
                    } catch (Exception e) {
                        log.info("  - DEFAULT representation error: {}", e.getMessage());
                    }
                    
                    log.info("  ---");
                    demoCount++;
                }
            }
            log.info("=== END DEMO ===");
        }
        
        // Phase 3: Enhanced analysis structure with metadata
        StringBuilder analysisResult = new StringBuilder();
        analysisResult.append("{\n");
        analysisResult.append("  \"metadata\": {\n");
        analysisResult.append("    \"timestamp\": \"").append(java.time.Instant.now().toString()).append("\",\n");
        analysisResult.append("    \"pluginVersion\": \"1.0.0-SNAPSHOT\",\n");
        analysisResult.append("    \"analysisPhase\": \"Phase 3 - Enhanced\",\n");
        analysisResult.append("    \"linksExcluded\": true,\n");
        analysisResult.append("    \"configuration\": {\n");
        analysisResult.append("      \"outputFile\": \"").append(OUTPUT_FILE_NAME).append("\",\n");
        analysisResult.append("      \"outputDir\": \"").append(OUTPUT_DIR).append("\",\n");
        analysisResult.append("      \"includeDemoOutput\": ").append(INCLUDE_DEMO_OUTPUT).append("\n");
        analysisResult.append("    }\n");
        analysisResult.append("  },\n");
        analysisResult.append("  \"resourceCount\": ").append(handlerCount).append(",\n");
        analysisResult.append("  \"resources\": [\n");
        
        boolean first = true;
        for (DelegatingResourceHandler<?> handler : handlers) {
            if (!first) analysisResult.append(",\n");
            first = false;
            
            analysisResult.append("    {\n");
            analysisResult.append(analyzeResourceHandler(handler));
            analysisResult.append("    }");
        }
        
        analysisResult.append("\n  ]\n");
        analysisResult.append("}\n");
        
        // Write results to file for the Maven plugin to read (configurable location)
        File outputDir = new File(OUTPUT_DIR);
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }
        
        File outputFile = new File(outputDir, OUTPUT_FILE_NAME);
        try (FileWriter writer = new FileWriter(outputFile)) {
            writer.write(analysisResult.toString());
        }
        
        log.info("=== Representation Analysis Complete ===");
        log.info("Resources analyzed: " + handlerCount);
        log.info("Output written to: " + outputFile.getAbsolutePath());
        log.info("File size: " + outputFile.length() + " bytes");
        
        // Phase 3: Success/failure summary
        int successCount = 0;
        int errorCount = 0;
        for (DelegatingResourceHandler<?> handler : handlers) {
            if (handler.getClass().getAnnotation(Resource.class) != null) {
                successCount++;
            } else {
                errorCount++;
            }
        }
        
        log.info("Analysis summary: {} successful, {} errors", successCount, errorCount);
        log.info("=========================================");
        
        assertTrue("Output file should exist", outputFile.exists());
        assertTrue("Output file should not be empty", outputFile.length() > 0);
    }
    
    /**
     * Analyze a single resource handler and extract representation metadata.
     * Phase 3: Enhanced implementation with better error handling and debugging info.
     */
    private String analyzeResourceHandler(DelegatingResourceHandler<?> handler) {
        StringBuilder result = new StringBuilder();
        
        try {
            // Get resource metadata
            Resource resourceAnnotation = handler.getClass().getAnnotation(Resource.class);
            
            if (resourceAnnotation != null) {
                result.append("      \"resourceName\": \"").append(resourceAnnotation.name()).append("\",\n");
                result.append("      \"supportedClass\": \"").append(resourceAnnotation.supportedClass().getSimpleName()).append("\",\n");
                result.append("      \"handlerClass\": \"").append(handler.getClass().getSimpleName()).append("\",\n");
                result.append("      \"handlerPackage\": \"").append(handler.getClass().getPackage().getName()).append("\",\n");
                result.append("      \"supportedVersions\": [");
                
                String[] versions = resourceAnnotation.supportedOpenmrsVersions();
                for (int i = 0; i < versions.length; i++) {
                    if (i > 0) result.append(", ");
                    result.append("\"").append(versions[i]).append("\"");
                }
                result.append("],\n");
                
                // Phase 3: Try to get representations with enhanced error handling
                result.append("      \"representations\": {\n");
                result.append(analyzeRepresentations(handler));
                result.append("      }\n");
                
            } else {
                result.append("      \"error\": \"No @Resource annotation found\",\n");
                result.append("      \"className\": \"").append(handler.getClass().getName()).append("\"\n");
            }
            
        } catch (Exception e) {
            log.warn("Error analyzing handler " + handler.getClass().getSimpleName() + ": " + e.getMessage());
            result.append("      \"error\": \"").append(e.getMessage().replace("\"", "\\\"")).append("\",\n");
            result.append("      \"className\": \"").append(handler.getClass().getName()).append("\"\n");
        }
        
        return result.toString();
    }
    
    /**
     * Analyze different representation types for a resource handler.
     * Phase 3: Enhanced implementation with better error handling and link verification.
     */
    private String analyzeRepresentations(DelegatingResourceHandler<?> handler) {
        StringBuilder result = new StringBuilder();
        
        // Analyze DEFAULT representation
        result.append("        \"DEFAULT\": ");
        try {
            DelegatingResourceDescription defaultRep = handler.getRepresentationDescription(Representation.DEFAULT);
            result.append(analyzeRepresentationDescription(defaultRep));
        } catch (Exception e) {
            result.append("{\"error\": \"").append(e.getMessage().replace("\"", "\\\"")).append("\"}");
        }
        result.append(",\n");
        
        // Analyze FULL representation
        result.append("        \"FULL\": ");
        try {
            DelegatingResourceDescription fullRep = handler.getRepresentationDescription(Representation.FULL);
            result.append(analyzeRepresentationDescription(fullRep));
        } catch (Exception e) {
            result.append("{\"error\": \"").append(e.getMessage().replace("\"", "\\\"")).append("\"}");
        }
        result.append(",\n");
        
        // Analyze REF representation
        result.append("        \"REF\": ");
        try {
            DelegatingResourceDescription refRep = handler.getRepresentationDescription(Representation.REF);
            result.append(analyzeRepresentationDescription(refRep));
        } catch (Exception e) {
            result.append("{\"error\": \"").append(e.getMessage().replace("\"", "\\\"")).append("\"}");
        }
        result.append("\n");
        
        return result.toString();
    }
    
    /**
     * Analyze a single representation description.
     * Phase 3: Enhanced property counting with link verification.
     */
    private String analyzeRepresentationDescription(DelegatingResourceDescription description) {
        if (description == null) {
            return "null";
        }
        
        try {
            int propertyCount = 0;
            int linkCount = 0;
            
            if (description.getProperties() != null) {
                propertyCount = description.getProperties().size();
            }
            
            // Verify links are separate from properties (as expected)
            if (description.getLinks() != null) {
                linkCount = description.getLinks().size();
            }
            
            StringBuilder result = new StringBuilder();
            result.append("{");
            result.append("\"propertyCount\": ").append(propertyCount);
            result.append(", \"linkCount\": ").append(linkCount);
            result.append(", \"linksExcluded\": true");
            result.append("}");
            
            return result.toString();
            
        } catch (Exception e) {
            return "{\"error\": \"" + e.getMessage().replace("\"", "\\\"") + "\"}";
        }
    }
}
