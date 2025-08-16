package org.openmrs.plugin.rest.analyzer;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Mojo(name = "analyze-representations", 
      defaultPhase = LifecyclePhase.PROCESS_CLASSES,
      requiresDependencyResolution = ResolutionScope.TEST)
public class RepresentationAnalyzerMojo extends AbstractMojo {
    
    private static final Logger log = LoggerFactory.getLogger(RepresentationAnalyzerMojo.class);

    @Parameter(defaultValue = "${project}", required = true, readonly = true)
    private MavenProject project;

    @Parameter(defaultValue = "300", property = "timeoutSeconds")
    private int timeoutSeconds;

    @Parameter(defaultValue = "2.4.x", property = "openmrsVersion")
    private String openmrsVersion;
    
    @Parameter(property = "scanPackages")
    private List<String> scanPackages;
    
    @Parameter(property = "autoDetectResources", defaultValue = "true")
    private boolean autoDetectResources;

    /**
     * Gets the hardcoded output directory for OpenAPI specifications.
     * Always uses target/openapi for consistency and predictability.
     * 
     * @return the output directory path
     */
    private String getOutputDirectory() {
        return project.getBuild().getDirectory() + "/openapi";
    }

    /**
     * Gets the hardcoded output file name for OpenAPI specifications.
     * Always uses openapi.json for consistency and predictability.
     * 
     * @return the output file name
     */
    private String getOutputFileName() {
        return "openapi.json";
    }

    /**
     * List of OpenMRS platform versions to generate OpenAPI specifications for.
     * When specified, the plugin will generate separate spec files for each version.
     * 
     * Example:
     * <versionsToGenerate>
     *     <version>2.3.4</version>
     *     <version>2.4.6</version>
     *     <version>2.5.0</version>
     * </versionsToGenerate>
     * 
     * Output files will be named: {moduleName}-openapi-spec-{version}.json
     */
    @Parameter(property = "versionsToGenerate")
    private List<String> versionsToGenerate;

    /**
     * Whether to generate a comparison report showing API differences between versions.
     * Only applicable when versionsToGenerate contains multiple versions.
     */
    @Parameter(property = "generateComparisonReport", defaultValue = "false")
    private boolean generateComparisonReport;

    /**
     * Whether to fail the build if any version-specific generation fails.
     * When false, the plugin will continue with other versions and report errors.
     */
    @Parameter(property = "failOnVersionError", defaultValue = "true")
    private boolean failOnVersionError;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        
        log.info("=== OpenMRS REST Representation Analyzer ===");
        log.info("Target module: {}", project.getArtifactId());
        log.debug("Project: {}", project.getName());
        log.debug("Output directory: {}", getOutputDirectory());
        
        // Validate and prepare configuration
        validateConfiguration();
        prepareScanPackages();
        prepareOutputDirectory();
        
        // Determine execution mode: single-version or multi-version
        List<String> targetVersions = determineTargetVersions();
        
        if (targetVersions.size() == 1) {
            // Single-version execution (current behavior)
            executeSingleVersion(targetVersions.get(0));
        } else {
            // Multi-version execution (new feature)
            executeMultipleVersions(targetVersions);
        }
    }

    /**
     * Validates the plugin configuration and reports any issues.
     */
    private void validateConfiguration() throws MojoExecutionException {
        if (versionsToGenerate != null && !versionsToGenerate.isEmpty()) {
            for (String version : versionsToGenerate) {
                if (!isValidVersion(version)) {
                    throw new MojoExecutionException("Invalid version format: " + version + 
                        ". Expected semantic version like '2.4.6' or '2.3.4'");
                }
            }
            
            // Check for duplicates
            if (versionsToGenerate.size() != versionsToGenerate.stream().distinct().count()) {
                throw new MojoExecutionException("Duplicate versions found in versionsToGenerate");
            }
            
            log.info("Multi-version generation enabled for {} versions: {}", 
                    versionsToGenerate.size(), versionsToGenerate);
        }
    }

    /**
     * Prepares scan packages using auto-detection if needed.
     */
    private void prepareScanPackages() {
        if (autoDetectResources && (scanPackages == null || scanPackages.isEmpty())) {
            scanPackages = ModuleClasspathBuilder.detectResourcePackages(project);
            log.info("Auto-detected resource packages: {}", scanPackages);
        } else if (scanPackages != null && !scanPackages.isEmpty()) {
            log.info("Using configured scan packages: {}", scanPackages);
        } else {
            log.warn("No scan packages specified and auto-detection disabled. May not find resources.");
            scanPackages = new ArrayList<>();
        }
        
        File outputDir = new File(getOutputDirectory());
        }
    }

    /**
     * Creates output directory if it doesn't exist.
     */
    private void prepareOutputDirectory() {
        File outputDir = new File(outputDirectory);
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }
    }

    /**
     * Determines which versions to generate specs for.
     */
    private List<String> determineTargetVersions() {
        if (versionsToGenerate != null && !versionsToGenerate.isEmpty()) {
            return new ArrayList<>(versionsToGenerate);
        } else {
            // Single-version mode using openmrsVersion parameter
            List<String> singleVersion = new ArrayList<>();
            singleVersion.add(openmrsVersion);
            return singleVersion;
        }
    }

    /**
     * Executes OpenAPI generation for a single OpenMRS version.
     */
    private void executeSingleVersion(String version) throws MojoExecutionException {
        log.info("Generating OpenAPI specification for OpenMRS version: {}", version);
        
        try {
            String versionSpecificOutputFile = outputFile; // Keep original filename for single version
            int exitCode = runTestInForkedProcess(version, versionSpecificOutputFile);
            
            if (exitCode != 0) {
                throw new MojoExecutionException("Test execution failed with exit code: " + exitCode);
            }
            
            processAnalysisResults();
            
            log.info("Representation analysis completed successfully");
            log.info("=============================="); 
        } catch (IOException | InterruptedException e) {
            handleExecutionError(e, version);
        }
    }

    /**
     * Executes OpenAPI generation for multiple OpenMRS versions.
     */
    private void executeMultipleVersions(List<String> versions) throws MojoExecutionException {
        log.info("=== Multi-Version OpenAPI Generation ===");
        log.info("Generating specifications for {} versions: {}", versions.size(), versions);
        
        List<String> successfulVersions = new ArrayList<>();
        List<String> failedVersions = new ArrayList<>();
        long startTime = System.currentTimeMillis();
        
        for (String version : versions) {
            try {
                log.info("--- Processing OpenMRS version: {} ---", version);
                
                String versionSpecificOutputFile = generateVersionSpecificFilename(version);
                int exitCode = runTestInForkedProcess(version, versionSpecificOutputFile);
                
                if (exitCode != 0) {
                    throw new MojoExecutionException("Test execution failed for version " + version + 
                        " with exit code: " + exitCode);
                }
                
                successfulVersions.add(version);
                log.info("✅ OpenMRS {}: {} generated successfully", version, versionSpecificOutputFile);
                
            } catch (Exception e) {
                failedVersions.add(version);
                log.error("❌ OpenMRS {}: Generation failed - {}", version, e.getMessage());
                
                if (failOnVersionError) {
                    throw new MojoExecutionException("Failed to generate spec for version " + version, e);
                }
            }
        }
        
        // Print summary
        printMultiVersionSummary(successfulVersions, failedVersions, startTime);
        
        if (generateComparisonReport && successfulVersions.size() > 1) {
            generateVersionComparisonReport(successfulVersions);
        }
        
        if (!failedVersions.isEmpty() && failOnVersionError) {
            throw new MojoExecutionException("One or more versions failed to generate");
        }
    }
    
    private int runTestInForkedProcess(String targetVersion, String targetOutputFile) throws IOException, InterruptedException {
        
        List<String> classpath = ModuleClasspathBuilder.buildTargetModuleClasspath(project);
        String classpathString = String.join(File.pathSeparator, classpath);
        
        log.debug("Forked process will use {} classpath entries for module: {}", 
                classpath.size(), project.getArtifactId());
        
        List<String> command = new ArrayList<>();
        command.add(getJavaExecutable());
        command.add("-cp");
        command.add(classpathString);
        
        command.add("-DdatabaseUrl=jdbc:h2:mem:openmrs;DB_CLOSE_DELAY=-1");
        command.add("-DdatabaseDriver=org.h2.Driver");
        command.add("-DuseInMemoryDatabase=true");
        command.add("-DdatabaseUsername=sa");
        command.add("-DdatabasePassword=");
        command.add("-Djava.awt.headless=true");
        
        command.add("-Dtarget.module.groupId=" + project.getGroupId());
        command.add("-Dtarget.module.artifactId=" + project.getArtifactId());
        command.add("-Dtarget.module.version=" + project.getVersion());
        command.add("-Dtarget.module.packages=" + String.join(",", scanPackages));
        command.add("-Dtarget.module.classesDir=" + project.getBuild().getOutputDirectory());
        
        command.add("-DanalysisOutputDir=" + getOutputDirectory());
        command.add("-DanalysisOutputFile=" + getOutputFileName());
        command.add("-Dopenmrs.version=" + openmrsVersion);
        command.add("-DanalysisOutputDir=" + outputDirectory);
        command.add("-DanalysisOutputFile=" + targetOutputFile);
        command.add("-Dopenmrs.version=" + targetVersion);
        
        command.add("org.junit.platform.console.ConsoleLauncher");
        command.add("--select-class");
        command.add("org.openmrs.plugin.rest.analyzer.test.OpenmrsOpenapiSpecGeneratorTest");
        
        log.info("Executing analysis for module: {} with packages: {}", 
                project.getArtifactId(), scanPackages);
        log.debug("Executing command: {}", String.join(" ", command));
        
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.inheritIO();
        pb.directory(project.getBasedir());
        
        Process process = pb.start();
        
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        
        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException("Test execution timed out after " + timeoutSeconds + " seconds");
        }
        
        return process.exitValue();
    }
    
    private String getJavaExecutable() {
        String javaHome = System.getProperty("java.home");
        if (javaHome == null) {
            return "java";
        }
        
        File javaExe = new File(javaHome, "bin/java");
        if (javaExe.exists()) {
            return javaExe.getAbsolutePath();
        }
        
        javaExe = new File(javaHome, "bin/java.exe");
        if (javaExe.exists()) {
            return javaExe.getAbsolutePath();
        }
        
        return "java";
    }
    
    private void processAnalysisResults() throws IOException {
        File expectedOutput = new File(getOutputDirectory(), getOutputFileName());
        
        if (!expectedOutput.exists()) {
            log.warn("Expected output file not found: {}", expectedOutput.getAbsolutePath());
            
            File targetDir = new File(getOutputDirectory());
            File[] jsonFiles = targetDir.listFiles((dir, name) -> name.endsWith(".json"));
            if (jsonFiles != null && jsonFiles.length > 0) {
                log.info("Found alternative output files:");
                for (File jsonFile : jsonFiles) {
                    log.info("  - {} ({} bytes)", jsonFile.getName(), jsonFile.length());
                }
            }
            return;
        }
        
        String content = new String(Files.readAllBytes(expectedOutput.toPath()));
        log.debug("=== Analysis Results Summary ===");
        log.debug("Analysis output: {}", expectedOutput.getAbsolutePath());
        log.debug("Output size: {} characters", content.length());
        
        if (content.contains("\"resourceCount\"")) {
            log.debug("Resource analysis completed successfully");
        } else if (content.contains("\"resources\"")) {
            log.debug("Resource analysis completed successfully");
        }
        
        File finalOutputFile = new File(getOutputDirectory(), getOutputFileName());
        Files.copy(expectedOutput.toPath(), finalOutputFile.toPath(), 
                  java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        log.debug("Final output: {}", finalOutputFile.getAbsolutePath());
        
        log.debug("==============================");
    }
    
    /**
     * Validates if a version string follows semantic versioning format.
     */
    private boolean isValidVersion(String version) {
        if (version == null || version.trim().isEmpty()) {
            return false;
        }
        
        // Basic semantic version validation (e.g., 2.4.6, 2.3.4)
        return version.matches("^\\d+\\.\\d+(\\.\\d+)?(\\.\\w+)*(-\\w+)?$");
    }
    
    /**
     * Generates version-specific output filename.
     */
    private String generateVersionSpecificFilename(String version) {
        String baseFilename = outputFile;
        
        // Remove .json extension if present
        if (baseFilename.endsWith(".json")) {
            baseFilename = baseFilename.substring(0, baseFilename.length() - 5);
        }
        
        return baseFilename + "-" + version + ".json";
    }
    
    /**
     * Handles execution errors for version-specific generation.
     */
    private void handleExecutionError(Exception e, String version) throws MojoExecutionException {
        log.error("Process execution error for version {}: {}", version, e.getMessage(), e);
        if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
        throw new MojoExecutionException("Failed to execute analysis process for version " + version, e);
    }
    
    /**
     * Prints a summary of multi-version generation results.
     */
    private void printMultiVersionSummary(List<String> successfulVersions, List<String> failedVersions, long startTime) {
        long totalTime = System.currentTimeMillis() - startTime;
        
        log.info("=== Multi-Version OpenAPI Generation Summary ===");
        
        if (!successfulVersions.isEmpty()) {
            log.info("✅ Successfully generated {} specification(s):", successfulVersions.size());
            for (String version : successfulVersions) {
                String filename = generateVersionSpecificFilename(version);
                File outputFile = new File(outputDirectory, filename);
                long fileSize = outputFile.exists() ? outputFile.length() : 0;
                log.info("   OpenMRS {}: {} ({} bytes)", version, filename, fileSize);
            }
        }
        
        if (!failedVersions.isEmpty()) {
            log.warn("❌ Failed to generate {} specification(s):", failedVersions.size());
            for (String version : failedVersions) {
                log.warn("   OpenMRS {}: Generation failed", version);
            }
        }
        
        log.info("Total execution time: {}s", totalTime / 1000.0);
        log.info("==============================");
    }
    
    /**
     * Generates a comparison report between different OpenMRS versions.
     * This is a placeholder for future implementation.
     */
    private void generateVersionComparisonReport(List<String> successfulVersions) {
        log.info("=== Version Comparison Report ===");
        log.info("📊 Comparison report for {} versions: {}", successfulVersions.size(), successfulVersions);
        
        // TODO: Implement version comparison logic
        // - Compare API endpoints across versions
        // - Identify new/removed/changed resources
        // - Generate diff report
        
        log.info("Note: Detailed version comparison is not yet implemented");
        log.info("Generated specifications can be manually compared using external tools");
        log.info("==============================");
    }
}
