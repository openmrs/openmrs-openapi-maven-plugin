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
     * - Plugin's test JAR (containing OpenmrsOpenapiSpecGeneratorTest)
     * - Module's compiled classes
     * - Module's test classes (for OpenMRS test context)
     * - All module dependencies (including webservices.rest if present)
     * - OpenMRS platform JARs
     * 
     * @param project The target module's Maven project
     * @return List of classpath entries as absolute file paths
     * @throws RuntimeException if plugin test JAR cannot be resolved
     */
    public static List<String> buildTargetModuleClasspath(MavenProject project) {
        log.debug("Building classpath for target module: {}", project.getArtifactId());
        
        List<String> classpath = new ArrayList<>();
        
        // 1. CRITICAL: Add plugin's test JAR first (contains OpenmrsOpenapiSpecGeneratorTest)
        String pluginTestJar = resolvePluginTestJar();
        classpath.add(pluginTestJar);
        log.info("Added plugin test JAR: {}", pluginTestJar);
        
        // 2. Target module's compiled classes
        String outputDir = project.getBuild().getOutputDirectory();
        if (outputDir != null) {
            classpath.add(outputDir);
            log.debug("Added module classes: {}", outputDir);
        }
        
        // 3. Target module's test classes (required for BaseModuleWebContextSensitiveTest)
        String testOutputDir = project.getBuild().getTestOutputDirectory();
        if (testOutputDir != null) {
            classpath.add(testOutputDir);
            log.debug("Added module test classes: {}", testOutputDir);
        }
        
        // 4. All target module's dependencies (including transitive)
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
        log.debug("  - Plugin test JAR: 1");
        log.debug("  - Module classes: 1");
        log.debug("  - Test classes: 1"); 
        log.debug("  - Dependencies: {}", dependencyCount);
        
        return classpath;
    }
    
    /**
     * Resolves the plugin's test JAR using multiple fallback strategies.
     * 
     * @return Absolute path to the plugin test JAR
     * @throws RuntimeException if test JAR cannot be found
     */
    private static String resolvePluginTestJar() {
        
        // Strategy 1: Development environment (plugin's own target directory)
        String devTestJar = tryDevelopmentEnvironment();
        if (devTestJar != null) {
            log.debug("Using development test JAR: {}", devTestJar);
            return devTestJar;
        }
        
        // Strategy 2: Maven repository
        String repoTestJar = tryMavenRepository();
        if (repoTestJar != null) {
            log.debug("Using repository test JAR: {}", repoTestJar);
            return repoTestJar;
        }
        
        // Strategy 3: Fail with helpful message
        throw new RuntimeException(createHelpfulErrorMessage());
    }
    
    /**
     * Try to find test JAR in development environment (plugin's own target directory).
     */
    private static String tryDevelopmentEnvironment() {
        try {
            // Check if we're running from the plugin's own directory
            File currentDir = new File(System.getProperty("user.dir"));
            File targetDir = new File(currentDir, "target");
            
            if (targetDir.exists()) {
                File[] testJars = targetDir.listFiles((dir, name) -> 
                    name.endsWith("-tests.jar") && name.contains("openmrs-rest-analyzer")
                );
                
                if (testJars != null && testJars.length > 0) {
                    return testJars[0].getAbsolutePath();
                }
            }
            
            // Also check parent directories (in case we're in a submodule)
            File parentDir = currentDir.getParentFile();
            if (parentDir != null) {
                File parentTarget = new File(parentDir, "openmrs-rest-representation-analyzer/target");
                if (parentTarget.exists()) {
                    File[] testJars = parentTarget.listFiles((dir, name) -> 
                        name.endsWith("-tests.jar") && name.contains("openmrs-rest-analyzer")
                    );
                    
                    if (testJars != null && testJars.length > 0) {
                        return testJars[0].getAbsolutePath();
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Could not resolve test JAR from development environment: {}", e.getMessage());
        }
        return null;
    }
    
    /**
     * Try to find test JAR in Maven repository.
     */
    private static String tryMavenRepository() {
        String[] repoPaths = getMavenRepositoryPaths();
        String version = "1.0.0-SNAPSHOT";  // TODO: Make this dynamic
        
        for (String repoPath : repoPaths) {
            if (repoPath == null) continue;
            
            String testJarPath = repoPath + File.separator +
                "org" + File.separator + 
                "openmrs" + File.separator + 
                "plugin" + File.separator + 
                "openmrs-rest-analyzer" + File.separator + 
                version + File.separator + 
                "openmrs-rest-analyzer-" + version + "-tests.jar";
            
            File testJar = new File(testJarPath);
            if (testJar.exists()) {
                return testJar.getAbsolutePath();
            }
        }
        return null;
    }
    
    /**
     * Get possible Maven repository paths for different platforms.
     */
    private static String[] getMavenRepositoryPaths() {
        List<String> paths = new ArrayList<>();
        
        // Unix/Mac standard
        String home = System.getProperty("user.home");
        if (home != null) {
            paths.add(home + File.separator + ".m2" + File.separator + "repository");
        }
        
        // Windows standard
        String userProfile = System.getenv("USERPROFILE");
        if (userProfile != null) {
            paths.add(userProfile + File.separator + ".m2" + File.separator + "repository");
        }
        
        // Custom repository
        String customRepo = System.getProperty("maven.repo.local");
        if (customRepo != null) {
            paths.add(customRepo);
        }
        
        return paths.toArray(new String[0]);
    }
    
    /**
     * Create a helpful error message for test JAR resolution failure.
     */
    private static String createHelpfulErrorMessage() {
        return "Cannot find plugin test JAR. Please ensure the plugin was installed correctly:\n" +
               "\n" +
               "1. Navigate to the plugin directory:\n" +
               "   cd /path/to/openmrs-rest-representation-analyzer\n" +
               "\n" +
               "2. Build and install the plugin:\n" +
               "   mvn clean install\n" +
               "\n" +
               "3. Verify test JAR exists:\n" +
               "   ls ~/.m2/repository/org/openmrs/plugin/openmrs-rest-analyzer/1.0.0-SNAPSHOT/\n" +
               "   (Should contain: openmrs-rest-analyzer-1.0.0-SNAPSHOT-tests.jar)\n" +
               "\n" +
               "If the problem persists, check that the maven-jar-plugin is configured correctly.";
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
