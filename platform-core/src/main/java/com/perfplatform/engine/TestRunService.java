package com.perfplatform.engine;

import com.perfplatform.config.TeamConfig;
import com.perfplatform.config.TeamConfigLoader;
import com.perfplatform.analysis.JvmDumpCollector;
import com.perfplatform.notification.NotificationService;
import com.perfplatform.report.ReportGenerator;
import com.perfplatform.sla.SlaEvaluator;
import com.perfplatform.storage.TestRun;
import com.perfplatform.storage.TestRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * High-level test run service that orchestrates the full lifecycle:
 * config load → pre-flight health check → JMeter run → SLA eval →
 * JVM dump analysis (opt-in) → report generation → notification
 */
@Service
public class TestRunService {

    private static final Logger log = LoggerFactory.getLogger(TestRunService.class);

    private final TeamConfigLoader configLoader;
    private final JmeterTestOrchestrator jmeter;
    private final SlaEvaluator slaEvaluator;
    private final JvmDumpCollector dumpCollector;
    private final ReportGenerator reportGenerator;
    private final NotificationService notificationService;
    private final TestRunRepository repository;

    @Autowired
    @Lazy
    private TestRunService self;

    public TestRunService(TeamConfigLoader configLoader,
            JmeterTestOrchestrator jmeter,
            SlaEvaluator slaEvaluator,
            JvmDumpCollector dumpCollector,
            ReportGenerator reportGenerator,
            NotificationService notificationService,
            TestRunRepository repository) {
        this.configLoader = configLoader;
        this.jmeter = jmeter;
        this.slaEvaluator = slaEvaluator;
        this.dumpCollector = dumpCollector;
        this.reportGenerator = reportGenerator;
        this.notificationService = notificationService;
        this.repository = repository;
    }

    /**
     * Creates a test run record and executes the test asynchronously.
     */
    public TestRun createAndRun(String team, String testType) {
        TeamConfig config = configLoader.getConfig(team)
                .orElseThrow(() -> new IllegalArgumentException("No config found for team: " + team));

        // Validate the requested test type is enabled in config
        validateTestTypeEnabled(config, testType);

        // Persist the run in PENDING state first (so caller can get the ID)
        TestRun run = new TestRun();
        run.setTeam(team);
        run.setTestType(TestRun.TestType.valueOf(testType.toUpperCase()));
        run.setStatus(TestRun.Status.PENDING);
        run = repository.save(run);

        // Execute asynchronously
        self.executeAsync(run.getId(), config, testType);
        return run;
    }

    @Async
    public void executeAsync(String runId, TeamConfig config, String testType) {
        TestRun run = repository.findById(runId).orElseThrow();
        run.setStatus(TestRun.Status.RUNNING);
        run.setStartedAt(Instant.now());
        repository.save(run);

        try {
            // 1. Run JMeter
            log.info("[{}] Starting {} test for team '{}'", runId, testType, config.getTeam());
            JmeterTestOrchestrator.JmeterRunResult result = jmeter.run(runId, config, testType);

            // 2. Evaluate SLA from results CSV
            SlaEvaluator.SlaResult sla = slaEvaluator.evaluate(result.resultsFile(), config.getSla());
            run.setP95LatencyMs(sla.p95LatencyMs());
            run.setErrorRatePercent(sla.errorRatePercent());
            run.setSlaResult(sla.passed() ? TestRun.SlaResult.PASS : TestRun.SlaResult.BREACH);

            // 3. JVM dump analysis — only if enabled in config
            for (TeamConfig.TargetConfig target : config.getTargets()) {
                if (target.isJvmEnabled()) {
                    if (config.getAnalysis().getHeapDump().isEnabled()) {
                        String heapReportPath = dumpCollector.collectAndAnalyzeHeapDump(runId, target,
                                config.getAnalysis().getHeapDump());
                        run.setHeapDumpReportPath(heapReportPath);
                        log.info("[{}] Heap dump analysis complete: {}", runId, heapReportPath);
                    }
                    if (config.getAnalysis().getThreadDump().isEnabled()) {
                        String threadReportPath = dumpCollector.collectAndAnalyzeThreadDump(runId, target);
                        run.setThreadDumpReportPath(threadReportPath);
                        log.info("[{}] Thread dump analysis complete: {}", runId, threadReportPath);
                    }
                }
            }

            run.setStatus(result.exitCode() == 0 ? TestRun.Status.COMPLETED : TestRun.Status.FAILED);
            if (result.exitCode() != 0) {
                run.setFailureReason("JMeter exited with code " + result.exitCode());
            }

            // 4. Generate unified HTML report
            String reportPath = reportGenerator.generate(runId, result, sla, run);
            run.setReportPath(reportPath);

        } catch (Exception e) {
            log.error("[{}] Test run failed", runId, e);
            run.setStatus(TestRun.Status.FAILED);
            run.setFailureReason(e.getMessage());
        } finally {
            run.setCompletedAt(Instant.now());
            repository.save(run);
            // 5. Notify team
            notificationService.notify(run, config.getNotifications());
        }
    }

    public Optional<TestRun> getById(String runId) {
        return repository.findById(runId);
    }

    public List<TestRun> getHistory(String team) {
        return repository.findByTeamOrderByCreatedAtDesc(team);
    }

    private void validateTestTypeEnabled(TeamConfig config, String testType) {
        boolean enabled = switch (testType.toUpperCase()) {
            case "LOAD" -> config.getTests().getLoad().isEnabled();
            case "ENDURANCE" -> config.getTests().getEndurance().isEnabled();
            case "SPIKE" -> config.getTests().getSpike().isEnabled();
            default -> throw new IllegalArgumentException("Unknown test type: " + testType);
        };
        if (!enabled) {
            throw new IllegalStateException(
                    "Test type '" + testType + "' is not enabled in config for team: " + config.getTeam());
        }
    }
}
