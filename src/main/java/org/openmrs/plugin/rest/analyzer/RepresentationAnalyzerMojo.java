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

    @Parameter(defaultValue = "${project.build.directory}/representation-analysis", property = "outputDirectory")
    private String outputDirectory;

    @Parameter(defaultValue = "representation-analysis.json", property = "outputFile")
    private String outputFile;

    @Parameter(defaultValue = "300", property = "timeoutSeconds")
    private int timeoutSeconds;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        
        log.info("=== OpenMRS REST Representation Analyzer ===");
        log.debug("Project: {}", project.getName());
        log.debug("Output directory: {}", outputDirectory);
        
        File outputDir = new File(outputDirectory);
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }
        
        try {
            log.debug("Running representation analysis in forked process...");
            
            int exitCode = runTestInForkedProcess();
            
            if (exitCode != 0) {
                throw new MojoExecutionException("Test execution failed with exit code: " + exitCode);
            }
            
            log.debug("Test completed successfully");
            
            processAnalysisResults();
            
            log.info("Representation analysis completed successfully");
            log.info("=============================="); 
        } catch (IOException | InterruptedException e) {
            log.error("Process execution error: {}", e.getMessage(), e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
                throw new MojoExecutionException("Failed to execute analysis process", e);
        } catch (RuntimeException e) {
            if (e.getMessage().contains("timeout")) {
                log.error("Analysis process timed out: {}", e.getMessage(), e);
                throw new MojoExecutionException("Analysis timed out after " + timeoutSeconds + " seconds", e);
            } else {
                log.error("Unexpected runtime error - this may indicate a bug: {}", e.getMessage(), e);
                throw e;
            }
        }
    }
    
    private int runTestInForkedProcess() throws IOException, InterruptedException {
        
        List<String> classpath = new ArrayList<>();
        
        classpath.add(project.getBuild().getOutputDirectory());
        classpath.add(project.getBuild().getTestOutputDirectory());
        
        for (Object artifact : project.getTestArtifacts()) {
            org.apache.maven.artifact.Artifact dep = (org.apache.maven.artifact.Artifact) artifact;
            if (dep.getFile() != null) {
                classpath.add(dep.getFile().getAbsolutePath());
            }
        }
        
        String classpathString = String.join(File.pathSeparator, classpath);
        log.debug("Forked process will use {} classpath entries", classpath.size());
        
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
        
        command.add("-DanalysisOutputDir=" + outputDirectory);
        command.add("-DanalysisOutputFile=" + outputFile);
        
        command.add("org.junit.runner.JUnitCore");
        command.add("org.openmrs.plugin.rest.analyzer.test.RepresentationAnalyzerTest");
        
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
        File expectedOutput = new File(project.getBuild().getDirectory(), "representation-analysis.json");
        
        if (!expectedOutput.exists()) {
            log.warn("Expected output file not found: {}", expectedOutput.getAbsolutePath());
            
            File targetDir = new File(project.getBuild().getDirectory());
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
        
        File finalOutputFile = new File(outputDirectory, outputFile);
        Files.copy(expectedOutput.toPath(), finalOutputFile.toPath(), 
                  java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        log.debug("Final output: {}", finalOutputFile.getAbsolutePath());
        
        log.debug("==============================");
    }
}
