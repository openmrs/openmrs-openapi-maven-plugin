package org.openmrs.plugin.rest.analyzer;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.project.MavenProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Utility class for building the complete runtime classpath of a target OpenMRS module.
 * This ensures the forked JVM has access to all necessary dependencies including
 * webservices.rest framework classes if the module depends on them.
 */
public class ModuleClasspathBuilder {
    
    private static final Logger log = LoggerFactory.getLogger(ModuleClasspathBuilder.class);
    
    /**
     * Builds the complete runtime classpath for the target module including:
     * - Module's compiled classes
     * - Module's test classes (for OpenMRS test context)
     * - All module dependencies (including webservices.rest if present)
     * - OpenMRS platform JARs
     * 
     * @param project The target module's Maven project
     * @return List of classpath entries as absolute file paths
     */
    public static List<String> buildTargetModuleClasspath(MavenProject project) {
        log.debug("Building classpath for target module: {}", project.getArtifactId());
        
        List<String> classpath = new ArrayList<>();
        
        // 1. Target module's compiled classes
        String outputDir = project.getBuild().getOutputDirectory();
        if (outputDir != null) {
            classpath.add(outputDir);
            log.debug("Added module classes: {}", outputDir);
        }
        
        // 2. Target module's test classes (required for BaseModuleWebContextSensitiveTest)
        String testOutputDir = project.getBuild().getTestOutputDirectory();
        if (testOutputDir != null) {
            classpath.add(testOutputDir);
            log.debug("Added module test classes: {}", testOutputDir);
        }
        
        // 3. All target module's dependencies (including transitive)
        int dependencyCount = 0;
        for (Object artifactObj : project.getTestArtifacts()) {
            Artifact artifact = (Artifact) artifactObj;
            File file = artifact.getFile();
            
            if (file != null && file.exists()) {
                classpath.add(file.getAbsolutePath());
                dependencyCount++;
                
                // Log important dependencies
                if (isImportantDependency(artifact)) {
                    log.debug("Added important dependency: {}:{}", 
                            artifact.getGroupId(), artifact.getArtifactId());
                }
            }
        }
        
        log.info("Built classpath with {} entries for module: {}", 
                classpath.size(), project.getArtifactId());
        log.debug("  - Module classes: 1");
        log.debug("  - Test classes: 1"); 
        log.debug("  - Dependencies: {}", dependencyCount);
        
        return classpath;
    }
    
    /**
     * Checks if an artifact is an important dependency worth logging.
     */
    private static boolean isImportantDependency(Artifact artifact) {
        String groupId = artifact.getGroupId();
        String artifactId = artifact.getArtifactId();
        
        return groupId.contains("openmrs") || 
               artifactId.contains("webservices") ||
               artifactId.contains("rest") ||
               groupId.contains("springframework");
    }
    
    /**
     * Extracts the module name from the artifact ID.
     * Examples: "openmrs-module-queue" -> "queue", "webservices.rest" -> "webservices.rest"
     */
    public static String extractModuleName(String artifactId) {
        if (artifactId.startsWith("openmrs-module-")) {
            return artifactId.substring("openmrs-module-".length());
        }
        return artifactId;
    }
    
    /**
     * Auto-detects likely resource package patterns for an OpenMRS module.
     */
    public static List<String> detectResourcePackages(MavenProject project) {
        String moduleName = extractModuleName(project.getArtifactId());
        String groupId = project.getGroupId();
        
        List<String> packages = new ArrayList<>();
        
        // Standard OpenMRS module patterns
        if (groupId.equals("org.openmrs.module")) {
            packages.add("org.openmrs.module." + moduleName + ".web.resources");
            packages.add("org.openmrs.module." + moduleName + ".web.resource");
            packages.add("org.openmrs.module." + moduleName + ".rest.resources");
        }
        
        // Special case for webservices.rest module
        if (moduleName.equals("webservices.rest")) {
            packages.add("org.openmrs.module.webservices.rest.web.v1_0.resource");
            packages.add("org.openmrs.module.webservices.rest.web.v2_0.resource");
        }
        
        // Generic fallback patterns
        packages.add(groupId + "." + moduleName + ".web.resources");
        packages.add(groupId + ".web.resources");
        
        log.debug("Auto-detected resource packages for {}: {}", project.getArtifactId(), packages);
        return packages;
    }
}
