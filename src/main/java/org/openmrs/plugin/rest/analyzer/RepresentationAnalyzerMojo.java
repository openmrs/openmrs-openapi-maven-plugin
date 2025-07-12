package org.openmrs.plugin.rest.analyzer;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

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

    @Parameter(defaultValue = "${project}", required = true, readonly = true)
    private MavenProject project;

    @Parameter(defaultValue = "${project.build.directory}/representation-analysis", property = "outputDirectory")
    private String outputDirectory;

    @Parameter(defaultValue = "representation-analysis.json", property = "outputFile")
    private String outputFile;

    @Parameter(defaultValue = "true", property = "generateReport")
    private boolean generateReport;

    @Parameter(defaultValue = "300", property = "timeoutSeconds")
    private int timeoutSeconds;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        
        getLog().info("=== OpenMRS REST Representation Analyzer (Forked Process) ===");
        getLog().info("Project: " + project.getName());
        getLog().info("Output directory: " + outputDirectory);
        
        // Ensure output directory exists
        File outputDir = new File(outputDirectory);
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }
        
        try {
            // Run test in forked process with correct classpath
            getLog().info("Running representation analysis in forked process...");
            
            int exitCode = runTestInForkedProcess();
            
            if (exitCode != 0) {
                throw new MojoExecutionException("Test execution failed with exit code: " + exitCode);
            }
            
            getLog().info("Test completed successfully");
            
            // Process results
            processAnalysisResults();
            
            getLog().info("Representation analysis completed successfully");
            
        } catch (Exception e) {
            getLog().error("Failed to analyze representations: " + e.getMessage(), e);
            throw new MojoExecutionException("Failed to analyze representations", e);
        }
    }
    
    private int runTestInForkedProcess() throws IOException, InterruptedException {
        
        // Build classpath from project dependencies
        List<String> classpath = new ArrayList<>();
        
        // Add compiled classes
        classpath.add(project.getBuild().getOutputDirectory());
        classpath.add(project.getBuild().getTestOutputDirectory());
        
        // Add all dependencies
        for (Object artifact : project.getTestArtifacts()) {
            org.apache.maven.artifact.Artifact dep = (org.apache.maven.artifact.Artifact) artifact;
            if (dep.getFile() != null) {
                classpath.add(dep.getFile().getAbsolutePath());
            }
        }
        
        String classpathString = String.join(File.pathSeparator, classpath);
        getLog().info("Forked process will use " + classpath.size() + " classpath entries");
        
        // Build command
        List<String> command = new ArrayList<>();
        command.add(getJavaExecutable());
        command.add("-cp");
        command.add(classpathString);
        
        // Add system properties for OpenMRS
        command.add("-DdatabaseUrl=jdbc:h2:mem:openmrs;DB_CLOSE_DELAY=-1");
        command.add("-DdatabaseDriver=org.h2.Driver");
        command.add("-DuseInMemoryDatabase=true");
        command.add("-DdatabaseUsername=sa");
        command.add("-DdatabasePassword=");
        command.add("-Djava.awt.headless=true");
        
        // Add output directory as system property
        command.add("-DanalysisOutputDir=" + outputDirectory);
        command.add("-DanalysisOutputFile=" + outputFile);
        
        // Run the test runner
        command.add("org.junit.runner.JUnitCore");
        command.add("org.openmrs.plugin.rest.analyzer.test.RepresentationAnalyzerTest");
        
        getLog().debug("Executing command: " + String.join(" ", command));
        
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.inheritIO(); // This will show test output in Maven log
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
        // Look for the output file created by the test (in target directory)
        File expectedOutput = new File(project.getBuild().getDirectory(), "representation-analysis.json");
        
        if (!expectedOutput.exists()) {
            getLog().warn("Expected output file not found: " + expectedOutput.getAbsolutePath());
            
            // Look for any JSON files in the target directory
            File targetDir = new File(project.getBuild().getDirectory());
            File[] jsonFiles = targetDir.listFiles((dir, name) -> name.endsWith(".json"));
            if (jsonFiles != null && jsonFiles.length > 0) {
                getLog().info("Found alternative output files:");
                for (File jsonFile : jsonFiles) {
                    getLog().info("  - " + jsonFile.getName() + " (" + jsonFile.length() + " bytes)");
                }
            }
            return;
        }
        
        // Read and log summary
        String content = new String(Files.readAllBytes(expectedOutput.toPath()));
        getLog().info("=== Analysis Results Summary ===");
        getLog().info("Analysis output: " + expectedOutput.getAbsolutePath());
        getLog().info("Output size: " + content.length() + " characters");
        
        // Extract basic metrics
        if (content.contains("\"resourceCount\"")) {
            // Could parse JSON here for detailed metrics
            getLog().info("✅ Resource analysis completed successfully");
        } else if (content.contains("\"resources\"")) {
            getLog().info("✅ Resource analysis completed successfully");
        }
        
        // Copy to final configured output location
        File finalOutputFile = new File(outputDirectory, outputFile);
        Files.copy(expectedOutput.toPath(), finalOutputFile.toPath(), 
                  java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        getLog().info("Final output: " + finalOutputFile.getAbsolutePath());
        
        getLog().info("==============================");
    }
}
