package com.perfplatform.engine;

import com.perfplatform.config.TeamConfig;
import com.perfplatform.config.TeamConfig.EndpointConfig;
import com.perfplatform.config.TeamConfig.ScenarioConfig;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Orchestrates JMeter test execution.
 *
 * For each test run:
 * 1. Generates a .jmx file from a Freemarker template + team config
 * 2. Runs JMeter as a subprocess (non-GUI mode)
 * 3. Streams JMeter output to a log file
 * 4. Returns the path to the JMeter results CSV + HTML report
 */
@Service
public class JmeterTestOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(JmeterTestOrchestrator.class);

    @Value("${platform.jmeter-home:/opt/jmeter}")
    private String jmeterHome;

    @Value("${platform.work-dir:/tmp/perf-platform}")
    private String workDir;

    private final Configuration freemarker;

    public JmeterTestOrchestrator(Configuration freemarker) {
        this.freemarker = freemarker;
    }

    public record JmeterRunResult(
            Path jmxFile,
            Path resultsFile,
            Path reportDir,
            Path logFile,
            int exitCode) {
    }

    /**
     * Executes a JMeter test for the given team config and test type.
     * The test is run synchronously — caller should invoke this from an @Async
     * method.
     */
    public JmeterRunResult run(String runId, TeamConfig config, String testType)
            throws IOException, InterruptedException {
        Path runDir = Paths.get(workDir, runId);
        Files.createDirectories(runDir);

        // 1. Generate JMX from template
        Path jmxFile = generateJmx(runDir, config, testType);

        // 2. Prepare output paths
        Path resultsFile = runDir.resolve("results.csv");
        Path reportDir = runDir.resolve("report");
        Path logFile = runDir.resolve("jmeter.log");

        // 3. Build JMeter command
        List<String> cmd = buildJmeterCommand(jmxFile, resultsFile, reportDir, logFile);
        log.info("[{}] Running JMeter: {}", runId, String.join(" ", cmd));

        // 4. Execute
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(runDir.toFile());
        pb.redirectErrorStream(true);
        pb.redirectOutput(logFile.toFile());

        Process process = pb.start();
        int exitCode = process.waitFor();

        log.info("[{}] JMeter finished with exit code: {}", runId, exitCode);
        return new JmeterRunResult(jmxFile, resultsFile, reportDir, logFile, exitCode);
    }

    private Path generateJmx(Path runDir, TeamConfig config, String testType) throws IOException {
        String templateName = switch (testType.toLowerCase()) {
            case "endurance" -> "endurance-test.jmx.ftl";
            case "spike" -> "spike-test.jmx.ftl";
            default -> "load-test.jmx.ftl";
        };

        Map<String, Object> model = buildTemplateModel(config, testType);

        try {
            Template template = freemarker.getTemplate("jmx/" + templateName);
            Path jmxFile = runDir.resolve("test.jmx");
            try (Writer writer = new FileWriter(jmxFile.toFile())) {
                template.process(model, writer);
            }
            return jmxFile;
        } catch (TemplateException e) {
            throw new IOException("Failed to generate JMX from template: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> buildTemplateModel(TeamConfig config, String testType) {
        Map<String, Object> model = new HashMap<>();
        model.put("config", config);
        model.put("testType", testType);

        // Primary target (first configured target)
        if (!config.getTargets().isEmpty()) {
            TeamConfig.TargetConfig target = config.getTargets().get(0);
            model.put("baseUrl", target.getBaseUrl());
            model.put("targetName", target.getName());
        }

        // Test-type specific settings
        switch (testType.toLowerCase()) {
            case "endurance" -> {
                model.put("users", config.getTests().getEndurance().getUsers());
                model.put("durationSeconds", config.getTests().getEndurance().getDurationMinutes() * 60);
                model.put("rampUpSeconds", 60);
            }
            case "spike" -> {
                model.put("baseUsers", config.getTests().getSpike().getBaseUsers());
                model.put("peakUsers", config.getTests().getSpike().getPeakUsers());
                model.put("spikeDurationSeconds", config.getTests().getSpike().getSpikeDurationSeconds());
            }
            default -> { // load
                model.put("rampUpUsers", config.getTests().getLoad().getRampUpUsers());
                model.put("steadyStateUsers", config.getTests().getLoad().getSteadyStateUsers());
                model.put("rampUpSeconds", config.getTests().getLoad().getRampUpDurationSeconds());
                model.put("steadyStateSeconds", config.getTests().getLoad().getSteadyStateDurationSeconds());
            }
        }

        // Flatten endpoints from all scenarios
        List<Map<String, String>> endpoints = new ArrayList<>();
        List<ScenarioConfig> scenarios = config.getTests().getLoad().getScenarios();
        if (scenarios != null) {
            for (ScenarioConfig scenario : scenarios) {
                for (EndpointConfig ep : scenario.getEndpoints()) {
                    Map<String, String> epMap = new HashMap<>();
                    epMap.put("method", ep.getMethod());
                    epMap.put("path", ep.getPath());
                    epMap.put("body", ep.getBody() != null ? ep.getBody() : "");
                    endpoints.add(epMap);
                }
            }
        }
        model.put("endpoints", endpoints);
        return model;
    }

    private List<String> buildJmeterCommand(Path jmxFile, Path resultsFile, Path reportDir, Path logFile) {
        String jmeterBin = Paths.get(jmeterHome, "bin", "jmeter").toString();
        return List.of(
                jmeterBin,
                "-n", // non-GUI mode
                "-t", jmxFile.toString(),
                "-l", resultsFile.toString(),
                "-e", // generate HTML report
                "-o", reportDir.toString(),
                "-j", logFile.toString());
    }
}
