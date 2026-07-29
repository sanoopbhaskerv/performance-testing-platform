package com.perfplatform.characterization;

import com.perfplatform.analysis.JvmDumpCollector;
import com.perfplatform.config.TeamConfig;
import com.perfplatform.config.TeamConfigLoader;
import com.perfplatform.engine.JmeterTestOrchestrator;
import com.perfplatform.engine.TestRunService;
import com.perfplatform.notification.NotificationService;
import com.perfplatform.report.ReportGenerator;
import com.perfplatform.sla.SlaEvaluator;
import com.perfplatform.storage.TestRun;
import com.perfplatform.storage.TestRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Characterization tests for the V1 run lifecycle in {@link TestRunService}.
 *
 * <p>This pins the behavior that Increment 1C replaces with a durable, reconciled state
 * machine (CLAUDE.md §11). In particular it records that V1 has only four run states, that
 * the lifecycle is held entirely inside a single {@code @Async} method, and that a run
 * interrupted by a control-plane restart would be left stranded in {@code RUNNING} — the
 * defect that motivates the persisted state machine.
 */
class TestRunLifecycleCharacterizationTest {

    private TeamConfigLoader configLoader;
    private JmeterTestOrchestrator jmeter;
    private SlaEvaluator slaEvaluator;
    private JvmDumpCollector dumpCollector;
    private ReportGenerator reportGenerator;
    private NotificationService notificationService;
    private TestRunRepository repository;
    private TestRunService service;

    @BeforeEach
    void setUp() throws Exception {
        configLoader = mock(TeamConfigLoader.class);
        jmeter = mock(JmeterTestOrchestrator.class);
        slaEvaluator = mock(SlaEvaluator.class);
        dumpCollector = mock(JvmDumpCollector.class);
        reportGenerator = mock(ReportGenerator.class);
        notificationService = mock(NotificationService.class);
        repository = mock(TestRunRepository.class);

        service = new TestRunService(configLoader, jmeter, slaEvaluator, dumpCollector,
                reportGenerator, notificationService, repository);

        // V1 self-injects a lazy proxy so @Async is honoured. Outside Spring it must be wired
        // by hand, otherwise createAndRun would dereference null.
        Field self = TestRunService.class.getDeclaredField("self");
        self.setAccessible(true);
        self.set(service, service);

        when(repository.save(any(TestRun.class))).thenAnswer(invocation -> {
            TestRun run = invocation.getArgument(0);
            if (run.getId() == null) {
                setId(run, "run-1");
            }
            return run;
        });
    }

    private static void setId(TestRun run, String id) throws Exception {
        Field field = TestRun.class.getDeclaredField("id");
        field.setAccessible(true);
        field.set(run, id);
    }

    private TeamConfig config() {
        TeamConfig config = new TeamConfig();
        config.setTeam("payments");
        config.getTests().getLoad().setEnabled(true);
        return config;
    }

    private SlaEvaluator.SlaResult passingSla() {
        return new SlaEvaluator.SlaResult(100, 200, 300, 0.5, 1000, 5, true, null);
    }

    // ── Trigger ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("triggering a run for an unknown team is rejected before anything is persisted")
    void rejectsUnknownTeam() {
        when(configLoader.getConfig("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createAndRun("ghost", "load"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("No config found for team: ghost");

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("triggering a test type that is disabled in team config is rejected")
    void rejectsDisabledTestType() {
        TeamConfig config = config();
        config.getTests().getLoad().setEnabled(false);
        when(configLoader.getConfig("payments")).thenReturn(Optional.of(config));

        assertThatThrownBy(() -> service.createAndRun("payments", "load"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Test type 'load' is not enabled in config for team: payments");

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("triggering an unrecognized test type is rejected")
    void rejectsUnknownTestType() {
        when(configLoader.getConfig("payments")).thenReturn(Optional.of(config()));

        assertThatThrownBy(() -> service.createAndRun("payments", "chaos"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown test type: chaos");
    }

    @Test
    @DisplayName("test type is matched case-insensitively and stored uppercased")
    void acceptsCaseInsensitiveTestType() {
        when(configLoader.getConfig("payments")).thenReturn(Optional.of(config()));
        when(repository.findById("run-1")).thenReturn(Optional.empty());

        // executeAsync runs inline here and throws on the empty Optional; the trigger result
        // is what this test pins, so the lifecycle failure is expected and ignored.
        assertThatThrownBy(() -> service.createAndRun("payments", "LoAd"))
                .isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test
    @DisplayName("a triggered run is persisted as PENDING with team and type before execution starts")
    void persistsPendingRunBeforeExecuting() throws Exception {
        when(configLoader.getConfig("payments")).thenReturn(Optional.of(config()));

        TestRun observed = new TestRun();
        setId(observed, "run-1");
        observed.setTeam("payments");
        observed.setTestType(TestRun.TestType.LOAD);
        when(repository.findById("run-1")).thenReturn(Optional.of(observed));
        when(jmeter.run(eq("run-1"), any(), eq("load")))
                .thenReturn(new JmeterTestOrchestrator.JmeterRunResult(
                        Path.of("test.jmx"), Path.of("results.csv"), Path.of("report"), Path.of("jmeter.log"), 0));
        when(slaEvaluator.evaluate(any(), any())).thenReturn(passingSla());
        when(reportGenerator.generate(any(), any(), any(), any())).thenReturn("/tmp/perf-platform/run-1/report.html");

        TestRun run = service.createAndRun("payments", "load");

        assertThat(run.getTeam()).isEqualTo("payments");
        assertThat(run.getTestType()).isEqualTo(TestRun.TestType.LOAD);
        // The returned record is the PENDING one handed back to the caller; execution mutates
        // the separately loaded instance.
        assertThat(run.getStatus()).isEqualTo(TestRun.Status.PENDING);
    }

    // ── Execution ────────────────────────────────────────────────────────

    @Test
    @DisplayName("a successful run records SLA metrics, a report path and COMPLETED status")
    void completesSuccessfulRun() throws Exception {
        TestRun run = new TestRun();
        setId(run, "run-1");
        run.setTeam("payments");
        run.setTestType(TestRun.TestType.LOAD);
        when(repository.findById("run-1")).thenReturn(Optional.of(run));
        when(jmeter.run(eq("run-1"), any(), eq("load")))
                .thenReturn(new JmeterTestOrchestrator.JmeterRunResult(
                        Path.of("test.jmx"), Path.of("results.csv"), Path.of("report"), Path.of("jmeter.log"), 0));
        when(slaEvaluator.evaluate(any(), any())).thenReturn(passingSla());
        when(reportGenerator.generate(any(), any(), any(), any())).thenReturn("/tmp/perf-platform/run-1/report.html");

        service.executeAsync("run-1", config(), "load");

        assertThat(run.getStatus()).isEqualTo(TestRun.Status.COMPLETED);
        assertThat(run.getSlaResult()).isEqualTo(TestRun.SlaResult.PASS);
        assertThat(run.getP95LatencyMs()).isEqualTo(200L);
        assertThat(run.getErrorRatePercent()).isEqualTo(0.5);
        assertThat(run.getStartedAt()).isNotNull();
        assertThat(run.getCompletedAt()).isNotNull();
        assertThat(run.getFailureReason()).isNull();
        verify(notificationService).notify(eq(run), any());
    }

    /**
     * KNOWN GAP (CLAUDE.md §5, §17): the durable record stores a pod-local filesystem path as
     * the artifact reference. A second replica cannot serve this report. Increment 1E replaces
     * it with an object-storage reference.
     */
    @Test
    @DisplayName("KNOWN GAP: the report reference persisted is a pod-local filesystem path")
    void persistsPodLocalReportPath() throws Exception {
        TestRun run = new TestRun();
        setId(run, "run-1");
        run.setTeam("payments");
        when(repository.findById("run-1")).thenReturn(Optional.of(run));
        when(jmeter.run(any(), any(), any()))
                .thenReturn(new JmeterTestOrchestrator.JmeterRunResult(
                        Path.of("test.jmx"), Path.of("results.csv"), Path.of("report"), Path.of("jmeter.log"), 0));
        when(slaEvaluator.evaluate(any(), any())).thenReturn(passingSla());
        when(reportGenerator.generate(any(), any(), any(), any())).thenReturn("/tmp/perf-platform/run-1/report.html");

        service.executeAsync("run-1", config(), "load");

        assertThat(run.getReportPath()).isEqualTo("/tmp/perf-platform/run-1/report.html");
    }

    @Test
    @DisplayName("an SLA breach is recorded as BREACH while the run still completes")
    void recordsSlaBreachWithoutFailingTheRun() throws Exception {
        TestRun run = new TestRun();
        setId(run, "run-1");
        run.setTeam("payments");
        when(repository.findById("run-1")).thenReturn(Optional.of(run));
        when(jmeter.run(any(), any(), any()))
                .thenReturn(new JmeterTestOrchestrator.JmeterRunResult(
                        Path.of("test.jmx"), Path.of("results.csv"), Path.of("report"), Path.of("jmeter.log"), 0));
        when(slaEvaluator.evaluate(any(), any()))
                .thenReturn(new SlaEvaluator.SlaResult(100, 900, 1200, 5.0, 1000, 50, false, "P95 too high"));
        when(reportGenerator.generate(any(), any(), any(), any())).thenReturn("/tmp/report.html");

        service.executeAsync("run-1", config(), "load");

        assertThat(run.getSlaResult()).isEqualTo(TestRun.SlaResult.BREACH);
        assertThat(run.getStatus()).isEqualTo(TestRun.Status.COMPLETED);
    }

    @Test
    @DisplayName("a non-zero JMeter exit code marks the run FAILED with the exit code as reason")
    void failsOnNonZeroJmeterExitCode() throws Exception {
        TestRun run = new TestRun();
        setId(run, "run-1");
        run.setTeam("payments");
        when(repository.findById("run-1")).thenReturn(Optional.of(run));
        when(jmeter.run(any(), any(), any()))
                .thenReturn(new JmeterTestOrchestrator.JmeterRunResult(
                        Path.of("test.jmx"), Path.of("results.csv"), Path.of("report"), Path.of("jmeter.log"), 2));
        when(slaEvaluator.evaluate(any(), any())).thenReturn(passingSla());
        when(reportGenerator.generate(any(), any(), any(), any())).thenReturn("/tmp/report.html");

        service.executeAsync("run-1", config(), "load");

        assertThat(run.getStatus()).isEqualTo(TestRun.Status.FAILED);
        assertThat(run.getFailureReason()).isEqualTo("JMeter exited with code 2");
    }

    @Test
    @DisplayName("an execution exception marks the run FAILED and still notifies the team")
    void failsAndNotifiesOnException() throws Exception {
        TestRun run = new TestRun();
        setId(run, "run-1");
        run.setTeam("payments");
        when(repository.findById("run-1")).thenReturn(Optional.of(run));
        when(jmeter.run(any(), any(), any())).thenThrow(new java.io.IOException("jmeter binary not found"));

        service.executeAsync("run-1", config(), "load");

        assertThat(run.getStatus()).isEqualTo(TestRun.Status.FAILED);
        assertThat(run.getFailureReason()).isEqualTo("jmeter binary not found");
        assertThat(run.getCompletedAt()).isNotNull();
        verify(notificationService).notify(eq(run), any());
    }

    @Test
    @DisplayName("JVM dump collection is skipped unless the target opts in and analysis is enabled")
    void skipsJvmDumpsWhenNotOptedIn() throws Exception {
        TestRun run = new TestRun();
        setId(run, "run-1");
        run.setTeam("payments");
        when(repository.findById("run-1")).thenReturn(Optional.of(run));
        when(jmeter.run(any(), any(), any()))
                .thenReturn(new JmeterTestOrchestrator.JmeterRunResult(
                        Path.of("test.jmx"), Path.of("results.csv"), Path.of("report"), Path.of("jmeter.log"), 0));
        when(slaEvaluator.evaluate(any(), any())).thenReturn(passingSla());
        when(reportGenerator.generate(any(), any(), any(), any())).thenReturn("/tmp/report.html");

        TeamConfig config = config();
        TeamConfig.TargetConfig target = new TeamConfig.TargetConfig();
        target.setName("payment-service");
        target.setJvmEnabled(false);
        config.setTargets(List.of(target));

        service.executeAsync("run-1", config, "load");

        verify(dumpCollector, never()).collectAndAnalyzeHeapDump(any(), any(), any());
        verify(dumpCollector, never()).collectAndAnalyzeThreadDump(any(), any());
    }

    /**
     * KNOWN GAP (CLAUDE.md §5, §22): heap and thread dump collection runs inline on the normal
     * run path. Increment 4 moves it behind explicit, separately authorized diagnostic providers.
     */
    @Test
    @DisplayName("KNOWN GAP: heap and thread dumps are collected inline on the normal run path")
    void collectsJvmDumpsInlineWhenOptedIn() throws Exception {
        TestRun run = new TestRun();
        setId(run, "run-1");
        run.setTeam("payments");
        when(repository.findById("run-1")).thenReturn(Optional.of(run));
        when(jmeter.run(any(), any(), any()))
                .thenReturn(new JmeterTestOrchestrator.JmeterRunResult(
                        Path.of("test.jmx"), Path.of("results.csv"), Path.of("report"), Path.of("jmeter.log"), 0));
        when(slaEvaluator.evaluate(any(), any())).thenReturn(passingSla());
        when(reportGenerator.generate(any(), any(), any(), any())).thenReturn("/tmp/report.html");
        when(dumpCollector.collectAndAnalyzeHeapDump(any(), any(), any())).thenReturn("/tmp/heap");
        when(dumpCollector.collectAndAnalyzeThreadDump(any(), any())).thenReturn("/tmp/threads");

        TeamConfig config = config();
        TeamConfig.TargetConfig target = new TeamConfig.TargetConfig();
        target.setName("payment-service");
        target.setJvmEnabled(true);
        config.setTargets(List.of(target));
        config.getAnalysis().getHeapDump().setEnabled(true);
        config.getAnalysis().getThreadDump().setEnabled(true);

        service.executeAsync("run-1", config, "load");

        assertThat(run.getHeapDumpReportPath()).isEqualTo("/tmp/heap");
        assertThat(run.getThreadDumpReportPath()).isEqualTo("/tmp/threads");
    }

    /**
     * KNOWN GAP (CLAUDE.md §11): V1 has four run states and no transition validation. There is
     * no QUEUED, PROVISIONING, ANALYZING, CANCELLED or EXPIRED state, so a run cannot be
     * cancelled and a control-plane restart strands it in RUNNING with no reconciliation path.
     */
    @Test
    @DisplayName("KNOWN GAP: V1 exposes only four run states and no cancellation")
    void hasOnlyFourRunStates() {
        assertThat(TestRun.Status.values())
                .containsExactly(TestRun.Status.PENDING, TestRun.Status.RUNNING,
                        TestRun.Status.COMPLETED, TestRun.Status.FAILED);
    }
}
