package com.perfplatform.orchestration.run;

import com.perfplatform.domain.admission.CapacityLimits;
import com.perfplatform.domain.admission.ConcurrencyAdmissionPolicy;
import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.identity.TestDefinitionVersionId;
import com.perfplatform.domain.identity.TestRunId;
import com.perfplatform.domain.port.DefinitionVersionCatalog.DefinitionVersion;
import com.perfplatform.domain.run.RunEvent;
import com.perfplatform.domain.run.RunEventType;
import com.perfplatform.domain.run.RunFailureCode;
import com.perfplatform.domain.run.RunState;
import com.perfplatform.domain.run.RunnerShard;
import com.perfplatform.domain.run.ShardState;
import com.perfplatform.domain.run.TestRun;
import com.perfplatform.orchestration.OrchestrationSettings;
import com.perfplatform.orchestration.execution.FakeExecutionProvider;
import com.perfplatform.orchestration.support.InMemoryTestRunRepository;
import com.perfplatform.orchestration.support.OrchestrationFixtures;
import com.perfplatform.orchestration.plan.EngineDispatchingPlanCompiler;
import com.perfplatform.orchestration.plan.EngineRegistry;
import com.perfplatform.orchestration.support.OrchestrationFixtures.InMemoryArtifacts;
import com.perfplatform.orchestration.support.OrchestrationFixtures.InMemoryPlanRepository;
import com.perfplatform.orchestration.support.OrchestrationFixtures.InMemoryShardRepository;
import com.perfplatform.orchestration.support.OrchestrationFixtures.StubDefinitionVersionCatalog;
import com.perfplatform.orchestration.support.OrchestrationFixtures.StubEngineProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.perfplatform.domain.port.ArtifactStore;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The control loop.
 *
 * <p>Everything here is driven by a mutable clock rather than by sleeps, so lease expiry, heartbeat
 * staleness and deadlines are exact rather than timing-dependent. A reconciliation test that slept
 * would be slow and would fail on a loaded CI machine for reasons unrelated to the code.
 */
class RunReconcilerTest {

    private static final Instant T0 = Instant.parse("2026-07-29T10:00:00Z");

    /** A clock the test advances explicitly. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MutableClock clock = new MutableClock(T0);
    private final InMemoryTestRunRepository runs = new InMemoryTestRunRepository();
    private final InMemoryShardRepository shards = new InMemoryShardRepository();
    private final InMemoryPlanRepository plans = new InMemoryPlanRepository();
    private final StubDefinitionVersionCatalog versions = new StubDefinitionVersionCatalog();
    private final InMemoryArtifacts artifacts = new InMemoryArtifacts();
    private final OrchestrationFixtures.InMemoryDatasets datasets =
            new OrchestrationFixtures.InMemoryDatasets();
    private final OrchestrationFixtures.InMemoryProgress progress =
            new OrchestrationFixtures.InMemoryProgress();
    private final EngineRegistry engines = new EngineRegistry(List.of(
            new StubEngineProvider(),
            new OrchestrationFixtures.StubBrowserEngineProvider()));

    private FakeExecutionProvider provider;

    /**
     * The provider the reconciler under test is given.
     *
     * <p>Wraps the fake so that it <em>declares</em> real load. The fake itself declares none, which
     * is correct and permanent — and which makes every run it executes reach {@code FAILED} with
     * {@code NO_LOAD_GENERATED}. Almost every test here is about the control loop rather than about
     * verdicts, and would otherwise assert the synthetic outcome over and over while leaving the
     * successful path untested. {@link SyntheticExecution} uses the unwrapped fake.
     */
    private com.perfplatform.domain.execution.ExecutionProvider declaredProvider;
    private InMemoryRuntimeCredentialStore runtimeCredentials;

    private TriggerTestRunUseCase trigger;
    private CancelTestRunUseCase cancel;
    private TeamId team;
    private DefinitionVersion version;

    @BeforeEach
    void setUp() {
        provider = new FakeExecutionProvider(clock, Duration.ZERO, Duration.ZERO,
                artifacts, artifacts);
        declaredProvider = OrchestrationFixtures.declaringRealLoad(provider);
        runtimeCredentials = new InMemoryRuntimeCredentialStore();
        trigger = new TriggerTestRunUseCase(runs, versions, runtimeCredentials, clock);
        cancel = new CancelTestRunUseCase(runs, clock);
        team = TeamId.generate();
        version = versions.add(team);
    }

    private RunReconciler reconciler(String owner) {
        return reconciler(owner, CapacityLimits.DEFAULTS);
    }

    private RunReconciler reconciler(String owner, CapacityLimits limits) {
        return reconciler(owner, limits, declaredProvider);
    }

    private RunReconciler reconciler(String owner, CapacityLimits limits,
            com.perfplatform.domain.execution.ExecutionProvider executionProvider) {

        return reconciler(owner, limits, executionProvider,
                new RuntimeCredentialSessionPoolProvider());
    }

    private RunReconciler reconciler(String owner, CapacityLimits limits,
            com.perfplatform.domain.execution.ExecutionProvider executionProvider,
            com.perfplatform.domain.port.SessionPoolProvider sessionPools) {

        RunWorkloadService workloads = new RunWorkloadService(engines, artifacts, artifacts,
                OrchestrationFixtures.permissiveAnalyzer(), OrchestrationFixtures.fixedTarget(),
                datasets, datasets, sessionPools, progress);

        return new RunReconciler(runs, shards, plans, versions,
                new EngineDispatchingPlanCompiler(engines), executionProvider,
                new ConcurrencyAdmissionPolicy(), limits, OrchestrationSettings.DEFAULTS,
                runtimeCredentials, workloads, clock, owner);
    }

    private TestRun queueRun(String key) {
        return trigger.trigger(new TriggerTestRunUseCase.TriggerCommand(version.id(), key),
                OrchestrationFixtures.caller(team)).run();
    }

    private TestRun queueSessionRun(String key) {
        version = versions.add(team, sessionSpecification(), true);
        return trigger.trigger(new TriggerTestRunUseCase.TriggerCommand(
                        version.id(), key, java.util.Map.of("session", "opaque-session-token")),
                OrchestrationFixtures.caller(team)).run();
    }

    private TestRun reload(TestRun run) {
        return runs.find(run.id()).orElseThrow();
    }

    private static com.perfplatform.domain.spec.PerformanceTestSpecification sessionSpecification() {
        var base = OrchestrationFixtures.apiSpecification();
        var body = base.spec();
        var workload = body.workloads().get(0);
        return new com.perfplatform.domain.spec.PerformanceTestSpecification(
                base.apiVersion(), base.kind(), base.metadata(),
                new com.perfplatform.domain.spec.PerformanceTestSpecification.TestSpec(
                        body.environment(), body.sources(),
                        java.util.List.of(
                                new com.perfplatform.domain.spec.PerformanceTestSpecification
                                        .AuthenticationSpec(
                                        "session", "bearer", "Authorization", "run://session")),
                        body.datasets(),
                        java.util.List.of(
                                new com.perfplatform.domain.spec.PerformanceTestSpecification
                                        .WorkloadSpec(
                                        workload.name(), workload.type(), workload.engine(),
                                        workload.sourceRef(), "session", workload.model(),
                                        workload.scenarios(), workload.startWhen(),
                                        workload.browserUsers(), workload.iterationsPerUser())),
                        body.objectives(), body.observability(), body.governance()));
    }

    @Nested
    @DisplayName("runtime session pools")
    class RuntimeSessionPools {

        @Test
        @DisplayName("session rows are delivered as an ephemeral runner secret file")
        void deliversSessionRowsAsSecretFile() {
            TestRun run = queueSessionRun("session-delivery");

            reconciler("reconciler-1").reconcile();

            RunnerShard shard = shards.findByRun(run.id()).get(0);
            var request = provider.requestFor(shard.id().value().toString()).orElseThrow();

            assertThat(request.datasets())
                    .noneMatch(dataset -> dataset.fileName().equals("sessions.csv"));

            assertThat(request.secretFiles())
                    .singleElement()
                    .satisfies(file -> {
                        assertThat(file.fileName()).isEqualTo("sessions.csv");
                        String csv = file.utf8Content();
                        assertThat(csv).startsWith("session_id,session_value,session_expires_at");
                        assertThat(csv).contains("opaque-session-token");
                        assertThat(csv.lines().count()).isEqualTo(9);
                        assertThat(file.toString()).doesNotContain("opaque-session-token");
                        assertThat(file.deleteAfter()).isAfterOrEqualTo(T0);
                    });

            assertThat(artifacts.listForRun(run.id()))
                    .noneMatch(reference -> reference.objectKey().contains("sessions.csv"));
            assertThat(artifacts.artifactsFor(run.id()))
                    .noneMatch(reference -> reference.objectKey().contains("sessions.csv"));
        }

        @Test
        @DisplayName("sessions that expire before the run deadline fail before provisioning")
        void refusesExpiringSessions() {
            TestRun run = queueSessionRun("session-expiry");

            reconciler("reconciler-1", CapacityLimits.DEFAULTS, declaredProvider,
                    new RuntimeCredentialSessionPoolProvider(Duration.ofSeconds(1)))
                    .reconcile();

            TestRun failed = reload(run);
            assertThat(failed.state()).isEqualTo(RunState.FAILED);
            assertThat(failed.failure().orElseThrow().code())
                    .isEqualTo(RunFailureCode.PROVISIONING_FAILED);
            assertThat(shards.findByRun(run.id())).isNotEmpty();
            RunnerShard shard = shards.findByRun(run.id()).get(0);
            assertThat(provider.requestFor(shard.id().value().toString())).isEmpty();
        }
    }

    /**
     * A provider that generates no load must never produce a run that reads as passing.
     *
     * <p>Uses the unwrapped {@link FakeExecutionProvider}, which is what a local development
     * deployment actually runs. Its measurements are invented, and the platform's job is to make that
     * impossible to miss rather than to render them as a green verdict.
     */
    /**
     * A runner that uploads its own output.
     *
     * <p>The Kubernetes case. A runner Pod writes to object storage and has no database access at
     * all — deliberately, since a provider that could write run state could mark its own failures as
     * successes. Reconciliation therefore has to notice what appeared, and check that it belongs to
     * this run's plan before believing any of it.
     */
    @Nested
    @DisplayName("results uploaded directly by a runner")
    class RunnerUploads {

        /** Drives a run to the point where analysis is about to happen, without producing results. */
        private TestRun runAwaitingAnalysis() {
            TestRun run = queueRun("baseline");
            RunReconciler reconciler = reconciler("reconciler-1");
            reconciler.reconcile();
            reconciler.reconcile();
            return reload(run);
        }

        private void upload(TestRun run, ArtifactStore.ArtifactType type, String fileName,
                String body) {
            RunnerShard shard = shards.findByRun(run.id()).get(0);
            artifacts.uploadedByRunner(new ArtifactStore.ArtifactDescriptor(
                    run.teamId(), run.id(), java.util.Optional.of(shard.id()),
                    type, fileName, "text/plain"), body.getBytes(StandardCharsets.UTF_8));
        }

        private String manifestWithDigest(TestRun run, String digest) {
            return """
                    {"runId":"%s","engine":"jmeter","planDigest":"%s","exitCode":0}"""
                    .formatted(run.id(), digest);
        }

        private String planDigestOf(TestRun run) {
            return plans.findByRun(run.id()).get(0).planDigest().value();
        }

        /**
         * Without adoption a Kubernetes run's output is invisible: the content is in storage and the
         * catalog knows nothing, so analysis finds no results and the run fails as if the runner had
         * produced nothing at all.
         */
        @Test
        @DisplayName("uploads are adopted into the catalog, so analysis can find them")
        void adoptsUploadedArtifacts() {
            TestRun run = runAwaitingAnalysis();
            upload(run, ArtifactStore.ArtifactType.RUNNER_LOG, "jmeter.log", "started\nfinished\n");

            assertThat(artifacts.artifactsFor(run.id()))
                    .as("before analysis, the catalog has only what the control plane stored")
                    .noneMatch(a -> a.objectKey().endsWith("jmeter.log"));

            reconciler("reconciler-1").reconcile();

            assertThat(artifacts.artifactsFor(run.id()))
                    .anyMatch(a -> a.objectKey().endsWith("jmeter.log"));
        }

        /** Reconciliation repeats. A second pass must not record a run's evidence twice. */
        @Test
        @DisplayName("adopting the same upload twice records it once")
        void adoptionIsIdempotent() {
            TestRun run = runAwaitingAnalysis();
            upload(run, ArtifactStore.ArtifactType.RUNNER_LOG, "jmeter.log", "output");

            reconciler("reconciler-1").reconcile();
            long afterFirst = artifacts.artifactsFor(run.id()).size();
            reconciler("reconciler-1").reconcile();

            assertThat(artifacts.artifactsFor(run.id())).hasSize((int) afterFirst);
        }

        /**
         * A stale Job, a reused Job name, or a runner handed the wrong key would otherwise
         * contribute real-looking measurements of a different test. The numbers would be genuine and
         * the report would look ordinary, which is exactly what makes accepting them dangerous.
         */
        @Test
        @DisplayName("results reporting a different plan digest are refused, not merged")
        void refusesResultsFromAnotherPlan() {
            TestRun run = runAwaitingAnalysis();
            upload(run, ArtifactStore.ArtifactType.RUNNER_MANIFEST, "runner-manifest.json",
                    manifestWithDigest(run, "sha256:" + "0".repeat(64)));

            reconciler("reconciler-1").reconcile();

            TestRun finished = reload(run);
            assertThat(finished.state()).isEqualTo(RunState.FAILED);
            assertThat(finished.failure().orElseThrow().code())
                    .isEqualTo(RunFailureCode.PLAN_DIGEST_MISMATCH);
        }

        @Test
        @DisplayName("results reporting this run's own plan digest are accepted")
        void acceptsResultsFromTheRunsOwnPlan() {
            TestRun run = runAwaitingAnalysis();
            upload(run, ArtifactStore.ArtifactType.RUNNER_MANIFEST, "runner-manifest.json",
                    manifestWithDigest(run, planDigestOf(run)));

            reconciler("reconciler-1").reconcile();

            assertThat(reload(run).failure().map(f -> f.code()))
                    .isNotEqualTo(java.util.Optional.of(RunFailureCode.PLAN_DIGEST_MISMATCH));
        }

        /**
         * An older runner, or one whose upload was cut short, has said nothing rather than said
         * something wrong. Treating silence as a mismatch would fail runs for a missing field.
         */
        @Test
        @DisplayName("a manifest with no digest is tolerated rather than treated as a mismatch")
        void manifestWithoutDigestIsTolerated() {
            TestRun run = runAwaitingAnalysis();
            upload(run, ArtifactStore.ArtifactType.RUNNER_MANIFEST, "runner-manifest.json",
                    "{\"runId\":\"" + run.id() + "\",\"exitCode\":0}");

            reconciler("reconciler-1").reconcile();

            assertThat(reload(run).failure().map(f -> f.code()))
                    .isNotEqualTo(java.util.Optional.of(RunFailureCode.PLAN_DIGEST_MISMATCH));
        }

        @Test
        @DisplayName("a digest is read out of a manifest whatever its whitespace")
        void digestIsReadFromVariedFormatting() {
            assertThat(RunWorkloadService.planDigestIn("{\"planDigest\":\"sha256:abc\"}"))
                    .contains("sha256:abc");
            assertThat(RunWorkloadService.planDigestIn("{ \"planDigest\" : \"sha256:abc\" }"))
                    .contains("sha256:abc");
            assertThat(RunWorkloadService.planDigestIn("{\"exitCode\":0}")).isEmpty();
        }
    }

    /**
     * Live monitoring, from the reconciler's side.
     *
     * <p>The rule underneath every test here is the asymmetry: a run must reach a terminal state
     * whatever happens to its live view, and a live view must never accept numbers it cannot prove
     * belong to the run. A convenience that can break the lifecycle is a liability, and a chart that
     * can quietly show another test's traffic is worse than no chart.
     */
    @Nested
    @DisplayName("live progress")
    class LiveProgressCollection {

        /** Drives a run to RUNNING, where a generator would be publishing snapshots. */
        private TestRun running() {
            TestRun run = queueRun("progress");
            reconciler("reconciler-1").reconcile();
            return reload(run);
        }

        private void publish(TestRun run, String document) {
            RunnerShard shard = shards.findByRun(run.id()).get(0);
            artifacts.uploadedByRunner(new ArtifactStore.ArtifactDescriptor(
                            run.teamId(), run.id(), java.util.Optional.of(shard.id()),
                            ArtifactStore.ArtifactType.PROGRESS,
                            com.perfplatform.domain.progress.ShardProgress.OBJECT_NAME,
                            "application/json"),
                    document.getBytes(StandardCharsets.UTF_8));
        }

        private String snapshot(TestRun run, String planDigest, long samples, long errors) {
            RunnerShard shard = shards.findByRun(run.id()).get(0);
            long[] latencies = new long[(int) samples];
            java.util.Arrays.fill(latencies, 120L);

            return com.perfplatform.domain.progress.ShardProgressJson.write(
                    new com.perfplatform.domain.progress.ShardProgress(
                            run.id(), shard.id(), 0, 1, "jmeter", planDigest,
                            clock.instant(), 60, 5, 25,
                            new com.perfplatform.domain.progress.ProgressMetrics(
                                    com.perfplatform.domain.results.OperationMetrics.ALL,
                                    samples, errors, 0, 10, 900, 120, 20_000,
                                    com.perfplatform.domain.progress.LatencyHistogram.of(latencies)),
                            java.util.Map.of()));
        }

        private String planDigestOf(TestRun run) {
            return plans.findByRun(run.id()).get(0).planDigest().value();
        }

        @Test
        @DisplayName("a published snapshot becomes a durable point in the run's series")
        void mergesPublishedSnapshots() {
            TestRun run = running();
            publish(run, snapshot(run, planDigestOf(run), 500, 12));

            reconciler("reconciler-1").reconcile();

            var latest = progress.latest(run.id()).orElseThrow();
            assertThat(latest.overall().sampleCount()).isEqualTo(500);
            assertThat(latest.overall().errorCount()).isEqualTo(12);
            assertThat(latest.activeUsers()).isEqualTo(25);
            assertThat(latest.shardsReporting()).isEqualTo(1);
        }

        /**
         * A stale object under a reused key would otherwise contribute real-looking numbers to the
         * wrong run's live view — where, unlike a report, nothing would ever look wrong about them.
         */
        @Test
        @DisplayName("a snapshot describing a different plan is discarded, not merged")
        void refusesAnotherPlansSnapshot() {
            TestRun run = running();
            publish(run, snapshot(run, "sha256:" + "0".repeat(64), 500, 0));

            reconciler("reconciler-1").reconcile();

            assertThat(progress.latest(run.id())).isEmpty();
        }

        /** Reconciliation repeats, and a duplicate point reads as sustained activity on a chart. */
        @Test
        @DisplayName("re-reading the same snapshot does not add a second point")
        void isIdempotent() {
            TestRun run = running();
            publish(run, snapshot(run, planDigestOf(run), 500, 0));

            reconciler("reconciler-1").reconcile();
            reconciler("reconciler-1").reconcile();

            assertThat(progress.series(run.id(), 100)).hasSize(1);
        }

        /**
         * The asymmetry, asserted directly. A truncated object caught mid-overwrite is the expected
         * case rather than an incident, and a run that could not finish because a chart could not be
         * drawn would be a strictly worse platform than one with no chart.
         */
        @Test
        @DisplayName("an unreadable snapshot costs a point on a chart, not the run")
        void unreadableSnapshotDoesNotStopTheRun() {
            TestRun run = running();
            publish(run, "{\"schema\":\"perfplatform.progress/v1\",\"runId\":");

            reconciler("reconciler-1").reconcile();
            reconciler("reconciler-1").reconcile();

            assertThat(progress.latest(run.id())).isEmpty();
            assertThat(reload(run).state().isTerminal()).isTrue();
        }

        /**
         * A snapshot is overwritten in place every few seconds, so a digest recorded for it stops
         * matching the object almost immediately — and the download path refuses content whose
         * digest does not match what was recorded.
         */
        @Test
        @DisplayName("a snapshot is not adopted as a run's evidence")
        void isNotAdoptedAsEvidence() {
            TestRun run = running();
            publish(run, snapshot(run, planDigestOf(run), 500, 0));

            reconciler("reconciler-1").reconcile();

            assertThat(artifacts.artifactsFor(run.id()))
                    .noneMatch(reference -> reference.type() == ArtifactStore.ArtifactType.PROGRESS);
        }
    }

    @Nested
    @DisplayName("execution that generates no load")
    class SyntheticExecution {

        private RunReconciler syntheticReconciler() {
            return reconciler("reconciler-1", CapacityLimits.DEFAULTS, provider);
        }

        @Test
        @DisplayName("the run is permanently marked synthetic, from provisioning onward")
        void runIsMarkedSynthetic() {
            TestRun run = queueRun("baseline");
            RunReconciler reconciler = syntheticReconciler();

            reconciler.reconcile();

            // Marked as soon as the provider was asked to start, not once results existed. The
            // caveat has to be true before there is any number for it to qualify.
            assertThat(reload(run).state()).isEqualTo(RunState.RUNNING);
            assertThat(reload(run).synthetic()).isTrue();

            reconciler.reconcile();
            reconciler.reconcile();

            assertThat(reload(run).synthetic())
                    .as("the marking survives every later transition")
                    .isTrue();
        }

        @Test
        @DisplayName("the run reaches a terminal state that is not COMPLETED")
        void runNeverCompletes() {
            TestRun run = queueRun("baseline");
            RunReconciler reconciler = syntheticReconciler();

            reconciler.reconcile();
            reconciler.reconcile();
            reconciler.reconcile();

            TestRun finished = reload(run);
            assertThat(finished.state().isTerminal()).isTrue();
            assertThat(finished.state()).isNotEqualTo(RunState.COMPLETED);
            assertThat(finished.failure().orElseThrow().code())
                    .isEqualTo(RunFailureCode.NO_LOAD_GENERATED);
            assertThat(finished.failure().orElseThrow().sanitizedReason())
                    .contains("generates no load");
        }

        @Test
        @DisplayName("the provider that executed the run is named in its history")
        void providerIsRecorded() {
            TestRun run = queueRun("baseline");
            syntheticReconciler().reconcile();

            assertThat(runs.events(run.id(), 0, 100))
                    .filteredOn(event -> event.type() == RunEventType.SHARD_PROVISIONED)
                    .allSatisfy(event -> assertThat(event.detail().get("provider"))
                            .isEqualTo(FakeExecutionProvider.PROVIDER_ID));
        }
    }

    @Nested
    @DisplayName("the happy path")
    class HappyPath {

        @Test
        @DisplayName("a queued run is admitted, provisioned and started in one pass")
        void queuedRunStarts() {
            TestRun run = queueRun("baseline");

            ReconciliationReport report = reconciler("reconciler-1").reconcile();

            assertThat(report.admitted()).isEqualTo(1);
            assertThat(reload(run).state()).isEqualTo(RunState.RUNNING);
            // The plans are the run's, found through the store rather than carried on the run: one
            // source of truth for what a run executes, whether it has one workload or several.
            assertThat(plans.findByRun(run.id())).hasSize(1);
            assertThat(plans.count()).isEqualTo(1);
        }

        /**
         * The handle is persisted before the run reports RUNNING. A crash between the two would
         * otherwise leave a load generator executing that nothing can find or cancel.
         */
        @Test
        @DisplayName("the shard's external handle is stored before the run reports running")
        void handleStoredBeforeRunning() {
            TestRun run = queueRun("baseline");
            reconciler("reconciler-1").reconcile();

            List<RunnerShard> created = shards.findByRun(run.id());
            assertThat(created).hasSize(1);
            assertThat(created.get(0).handle()).isPresent();
            assertThat(provider.hasWorkload(created.get(0).id().value().toString())).isTrue();
        }

        @Test
        @DisplayName("the runner is told the run id, plan digest and shard partition")
        void runnerReceivesCorrelationAndPartition() {
            TestRun run = queueRun("baseline");
            reconciler("reconciler-1").reconcile();

            RunnerShard shard = shards.findByRun(run.id()).get(0);

            assertThat(provider.requestFor(shard.id().value().toString())).hasValueSatisfying(request -> {
                assertThat(request.runId()).isEqualTo(run.id());
                assertThat(request.teamId()).isEqualTo(team);
                assertThat(request.shardIndex()).isZero();
                assertThat(request.shardCount()).isEqualTo(1);
                assertThat(request.planDigest()).isEqualTo(
                        plans.findByRun(run.id()).get(0)
                                .planDigest());
                // The deadline is the plan's duration plus the configured grace, so a workload that
                // ignores its stages still stops.
                assertThat(request.deadline()).isGreaterThan(Duration.ofMinutes(15));
            });
        }

        @Test
        @DisplayName("a finished runner drives the run through analysis to completion")
        void runReachesCompleted() {
            TestRun run = queueRun("baseline");
            RunReconciler reconciler = reconciler("reconciler-1");

            reconciler.reconcile();
            assertThat(reload(run).state()).isEqualTo(RunState.RUNNING);

            reconciler.reconcile();
            assertThat(reload(run).state()).isEqualTo(RunState.ANALYZING);

            assertThat(reconciler.reconcile().completed()).isEqualTo(1);
            assertThat(reload(run).state()).isEqualTo(RunState.COMPLETED);
            assertThat(reload(run).timeline().completedAt()).isPresent();
        }

        @Test
        @DisplayName("every state change left an event behind")
        void everyTransitionRecorded() {
            TestRun run = queueRun("baseline");
            RunReconciler reconciler = reconciler("reconciler-1");
            reconciler.reconcile();
            reconciler.reconcile();
            reconciler.reconcile();

            assertThat(runs.events(run.id(), 0, 100))
                    .filteredOn(event -> event.type() == RunEventType.STATE_CHANGED)
                    .extracting(event -> event.newState().orElseThrow())
                    .containsExactly(RunState.QUEUED, RunState.PROVISIONING, RunState.RUNNING,
                            RunState.ANALYZING, RunState.COMPLETED);
        }
    }

    @Nested
    @DisplayName("hybrid workload coordination")
    class HybridCoordination {

        @Test
        @DisplayName("browser shards stay pending until protocol load reaches the named stage with samples")
        void browserStartsAfterProtocolStageAndSamples() {
            provider = new FakeExecutionProvider(clock, Duration.ZERO, Duration.ofMinutes(30),
                    artifacts, artifacts);
            declaredProvider = OrchestrationFixtures.declaringRealLoad(provider);
            version = versions.add(team, hybridSpecification(), true);
            TestRun run = trigger.trigger(new TriggerTestRunUseCase.TriggerCommand(
                    version.id(), "hybrid"), OrchestrationFixtures.caller(team)).run();
            RunReconciler reconciler = reconciler("reconciler-1");

            reconciler.reconcile();

            List<RunnerShard> initial = shards.findByRun(run.id());
            assertThat(initial).hasSize(2);
            assertThat(initial)
                    .filteredOn(shard -> shard.workloadName().equals("api-background-load"))
                    .singleElement()
                    .satisfies(shard -> assertThat(shard.handle()).isPresent());
            assertThat(initial)
                    .filteredOn(shard -> shard.workloadName().equals("browser-experience"))
                    .singleElement()
                    .satisfies(shard -> {
                        assertThat(shard.state()).isEqualTo(ShardState.PENDING);
                        assertThat(shard.handle()).isEmpty();
                    });

            reconciler.reconcile();
            assertThat(shards.findByRun(run.id()))
                    .filteredOn(shard -> shard.workloadName().equals("browser-experience"))
                    .singleElement()
                    .satisfies(shard -> assertThat(shard.handle()).isEmpty());

            progress.record(progressPoint(run.id(), clock.instant(), 120));
            clock.advance(Duration.ofMinutes(5));

            reconciler.reconcile();

            RunnerShard browser = shards.findByRun(run.id()).stream()
                    .filter(shard -> shard.workloadName().equals("browser-experience"))
                    .findFirst()
                    .orElseThrow();
            assertThat(browser.handle()).isPresent();
            assertThat(runs.events(run.id(), 0, 100))
                    .extracting(RunEvent::type)
                    .contains(RunEventType.WORKLOAD_DEFERRED,
                            RunEventType.WORKLOAD_START_ELIGIBLE);
        }
    }

    @Nested
    @DisplayName("admission")
    class Admission {

        @Test
        @DisplayName("one team's ten queued runs start one at a time")
        void perTeamLimitHolds() {
            for (int i = 0; i < 10; i++) {
                queueRun("baseline-" + i);
            }

            reconciler("reconciler-1").reconcile();

            assertThat(runs.allRuns()).filteredOn(TestRun::holdsCapacity).hasSize(1);
            assertThat(runs.allRuns()).filteredOn(run -> run.state() == RunState.QUEUED).hasSize(9);
        }

        @Test
        @DisplayName("a freed slot admits the next queued run")
        void freedSlotAdmitsNext() {
            queueRun("first");
            queueRun("second");
            RunReconciler reconciler = reconciler("reconciler-1");

            reconciler.reconcile();
            reconciler.reconcile();
            reconciler.reconcile();

            // The first run completed, releasing the team's only slot; observing before admitting
            // means the same pass starts the next one.
            assertThat(runs.allRuns()).filteredOn(run -> run.state() == RunState.COMPLETED).hasSize(1);
            assertThat(runs.allRuns()).filteredOn(TestRun::holdsCapacity).hasSize(1);
        }

        @Test
        @DisplayName("the global ceiling holds across teams")
        void globalLimitHolds() {
            CapacityLimits twoGlobal = new CapacityLimits(2, 1, java.util.Map.of());

            for (int i = 0; i < 5; i++) {
                TeamId otherTeam = TeamId.generate();
                DefinitionVersion otherVersion = versions.add(otherTeam);
                trigger.trigger(
                        new TriggerTestRunUseCase.TriggerCommand(otherVersion.id(), "run-" + i),
                        OrchestrationFixtures.caller(otherTeam));
            }

            reconciler("reconciler-1", twoGlobal).reconcile();

            assertThat(runs.allRuns()).filteredOn(TestRun::holdsCapacity).hasSize(2);
        }
    }

    @Nested
    @DisplayName("recovery after a control-plane restart")
    class Recovery {

        /**
         * The core durability claim. A reconciler that dies mid-run leaves leases that expire, and
         * a different instance picks the runs up without an operator being involved.
         */
        @Test
        @DisplayName("a lapsed lease lets another reconciler take the run over")
        void lapsedLeaseIsTakenOver() {
            TestRun run = queueRun("baseline");
            reconciler("reconciler-1").reconcile();
            assertThat(reload(run).claim().orElseThrow().owner()).isEqualTo("reconciler-1");

            // reconciler-1 dies here. Nothing is done on its behalf.
            clock.advance(OrchestrationSettings.DEFAULTS.leaseDuration().plusMinutes(1));

            RunReconciler survivor = reconciler("reconciler-2");
            survivor.reconcile();

            assertThat(reload(run).claim().orElseThrow().owner()).isEqualTo("reconciler-2");
            assertThat(runs.events(run.id(), 0, 100))
                    .extracting(event -> event.type().name())
                    .contains(RunEventType.CLAIM_EXPIRED.name());
        }

        @Test
        @DisplayName("a run in flight still reaches a terminal state after the restart")
        void runFinishesAfterRestart() {
            TestRun run = queueRun("baseline");
            reconciler("reconciler-1").reconcile();

            clock.advance(OrchestrationSettings.DEFAULTS.leaseDuration().plusMinutes(1));

            RunReconciler survivor = reconciler("reconciler-2");
            survivor.reconcile();
            survivor.reconcile();

            assertThat(reload(run).state()).isEqualTo(RunState.COMPLETED);
        }

        /**
         * A trigger creates the run and queues it in two steps. A process dying between them would
         * otherwise leave a run in VALIDATING that nothing ever looks at again.
         */
        @Test
        @DisplayName("a run stranded mid-trigger is recovered and queued")
        void strandedValidatingRunIsRecovered() {
            TestRun stranded = TestRun.request(TestRunId.generate(), team, version.id(),
                    "performance",
                    new TestRun.TriggerContext("interrupted", "sha256:abc", "alice@example.com"),
                    T0).run();
            runs.seed(stranded);

            // Inside the grace period the sweep leaves it alone: the trigger may still be in flight.
            reconciler("reconciler-1").reconcile();
            assertThat(reload(stranded).state()).isEqualTo(RunState.VALIDATING);

            clock.advance(OrchestrationSettings.DEFAULTS.validationGrace().plusSeconds(30));
            assertThat(reconciler("reconciler-1").reconcile().validated()).isEqualTo(1);
            assertThat(reload(stranded).state()).isIn(RunState.QUEUED, RunState.PROVISIONING,
                    RunState.RUNNING);
        }

        @Test
        @DisplayName("a stranded run whose version is gone is rejected rather than run blindly")
        void strandedRunWithMissingVersionIsRejected() {
            TestDefinitionVersionId missing = TestDefinitionVersionId.generate();
            TestRun stranded = TestRun.request(TestRunId.generate(), team, missing, "performance",
                    new TestRun.TriggerContext("orphan", "sha256:abc", "alice@example.com"), T0).run();
            runs.seed(stranded);

            clock.advance(OrchestrationSettings.DEFAULTS.validationGrace().plusSeconds(30));
            reconciler("reconciler-1").reconcile();

            assertThat(reload(stranded).state()).isEqualTo(RunState.REJECTED);
        }

        /**
         * Provisioning is the one step that is not naturally repeatable, because it creates load.
         * A shard that already has a handle must never be provisioned again.
         */
        @Test
        @DisplayName("a repeated provisioning pass does not start a second runner for a shard")
        void provisioningIsNotRepeated() {
            TestRun run = queueRun("baseline");
            reconciler("reconciler-1").reconcile();

            RunnerShard shard = shards.findByRun(run.id()).get(0);
            String uid = shard.id().value().toString();

            // Force the run back through provisioning as a crashed pass would have left it.
            runs.seed(rewindToProvisioning(reload(run)));
            reconciler("reconciler-1").reconcile();

            assertThat(shards.findByRun(run.id())).hasSize(1);
            assertThat(shards.findByRun(run.id()).get(0).id().value().toString()).isEqualTo(uid);
        }
    }

    @Nested
    @DisplayName("cancellation")
    class Cancellation {

        @Test
        @DisplayName("a requested cancellation stops the runner and finishes the run")
        void cancellationTerminatesRunner() {
            TestRun run = queueRun("baseline");
            RunReconciler reconciler = reconciler("reconciler-1");
            reconciler.reconcile();

            cancel.cancel(run.id(), "Wrong target", OrchestrationFixtures.caller(team));
            assertThat(reload(run).state()).isEqualTo(RunState.RUNNING);

            assertThat(reconciler.reconcile().cancelled()).isEqualTo(1);
            assertThat(reload(run).state()).isEqualTo(RunState.CANCELLED);
            assertThat(shards.findByRun(run.id()).get(0).state()).isEqualTo(ShardState.CANCELLED);
        }

        @Test
        @DisplayName("terminating the runner is recorded before the run is marked cancelled")
        void terminationIsRecorded() {
            TestRun run = queueRun("baseline");
            RunReconciler reconciler = reconciler("reconciler-1");
            reconciler.reconcile();
            cancel.cancel(run.id(), "Wrong target", OrchestrationFixtures.caller(team));
            reconciler.reconcile();

            assertThat(runs.events(run.id(), 0, 100))
                    .extracting(event -> event.type().name())
                    .contains(RunEventType.EXECUTION_TERMINATED.name());
        }

        @Test
        @DisplayName("a cancelled run releases its capacity slot")
        void cancelledRunFreesCapacity() {
            TestRun run = queueRun("baseline");
            RunReconciler reconciler = reconciler("reconciler-1");
            reconciler.reconcile();
            cancel.cancel(run.id(), null, OrchestrationFixtures.caller(team));
            reconciler.reconcile();

            assertThat(runs.currentCapacity().globalActive()).isZero();
        }
    }

    @Nested
    @DisplayName("failures")
    class Failures {

        @Test
        @DisplayName("a runner that vanishes fails the run rather than completing it")
        void lostRunnerFailsTheRun() {
            TestRun run = queueRun("baseline");
            RunReconciler reconciler = reconciler("reconciler-1");
            reconciler.reconcile();

            // The provider forgets the workload, as a cluster would after a Job was deleted.
            provider.reset();
            reconciler.reconcile();

            assertThat(reload(run).state()).isEqualTo(RunState.FAILED);
            assertThat(reload(run).failure().orElseThrow().code())
                    .isEqualTo(RunFailureCode.RUNNER_LOST);
        }

        @Test
        @DisplayName("a failed run is never restarted")
        void failedRunIsNotReplayed() {
            TestRun run = queueRun("baseline");
            RunReconciler reconciler = reconciler("reconciler-1");
            reconciler.reconcile();
            provider.reset();
            reconciler.reconcile();

            // A retried performance test is duplicate load against a real system and two
            // overlapping sets of results.
            int shardCountAfterFailure = shards.findByRun(run.id()).size();
            reconciler.reconcile();
            reconciler.reconcile();

            assertThat(reload(run).state()).isEqualTo(RunState.FAILED);
            assertThat(shards.findByRun(run.id())).hasSize(shardCountAfterFailure);
        }

        @Test
        @DisplayName("one failing run does not stop the pass for other runs")
        void oneFailureDoesNotStallThePass() {
            TeamId otherTeam = TeamId.generate();
            DefinitionVersion otherVersion = versions.add(otherTeam);

            TestRun broken = TestRun.request(TestRunId.generate(), team,
                    TestDefinitionVersionId.generate(), "performance",
                    new TestRun.TriggerContext("broken", "sha256:abc", "alice@example.com"), T0)
                    .run().transitionTo(RunState.QUEUED, "alice", null, T0).run();
            runs.seed(broken);

            TestRun healthy = trigger.trigger(
                    new TriggerTestRunUseCase.TriggerCommand(otherVersion.id(), "healthy"),
                    OrchestrationFixtures.caller(otherTeam)).run();

            reconciler("reconciler-1").reconcile();

            assertThat(reload(broken).state()).isEqualTo(RunState.FAILED);
            assertThat(reload(healthy).state()).isEqualTo(RunState.RUNNING);
        }
    }

    /** Puts a run back into PROVISIONING as a pass that crashed mid-provision would have left it. */
    private static com.perfplatform.domain.spec.PerformanceTestSpecification hybridSpecification() {
        var base = OrchestrationFixtures.apiSpecification();
        var body = base.spec();
        var protocol = new com.perfplatform.domain.spec.PerformanceTestSpecification.WorkloadSpec(
                "api-background-load",
                com.perfplatform.domain.spec.WorkloadType.PROTOCOL,
                "jmeter",
                null,
                new com.perfplatform.domain.spec.PerformanceTestSpecification.LoadModel(
                        com.perfplatform.domain.spec.LoadModelType.STAGES,
                        List.of(
                                new com.perfplatform.domain.spec.PerformanceTestSpecification.LoadStage(
                                        "ramp-up", Duration.ofMinutes(5), 100, null),
                                new com.perfplatform.domain.spec.PerformanceTestSpecification.LoadStage(
                                        "steady-state", Duration.ofMinutes(10), 200, null),
                                new com.perfplatform.domain.spec.PerformanceTestSpecification.LoadStage(
                                        "ramp-down", Duration.ofMinutes(1), 0, null))),
                body.workloads().get(0).scenarios(),
                null,
                null,
                null);
        var browser = new com.perfplatform.domain.spec.PerformanceTestSpecification.WorkloadSpec(
                "browser-experience",
                com.perfplatform.domain.spec.WorkloadType.BROWSER,
                "playwright",
                null,
                null,
                null,
                List.of(),
                new com.perfplatform.domain.spec.PerformanceTestSpecification.StartCondition(
                        "api-background-load", "steady-state"),
                5,
                1);
        return new com.perfplatform.domain.spec.PerformanceTestSpecification(
                base.apiVersion(), base.kind(), base.metadata(),
                new com.perfplatform.domain.spec.PerformanceTestSpecification.TestSpec(
                        body.environment(), body.sources(), body.authentication(), body.datasets(),
                        List.of(protocol, browser), body.objectives(), body.observability(),
                        body.governance()));
    }

    private static com.perfplatform.domain.progress.LiveProgress progressPoint(TestRunId runId,
            Instant at, long samples) {
        long[] latencies = new long[(int) samples];
        java.util.Arrays.fill(latencies, 120L);
        var metrics = new com.perfplatform.domain.progress.ProgressMetrics(
                com.perfplatform.domain.results.OperationMetrics.ALL,
                samples, 0, 0, 10, 900, 120, 20_000,
                com.perfplatform.domain.progress.LatencyHistogram.of(latencies));
        return new com.perfplatform.domain.progress.LiveProgress(
                runId, "jmeter", at, at, 1, 60, 100, metrics, Map.of(),
                List.of(new com.perfplatform.domain.progress.LiveProgress.ShardReport(
                        0, at, samples, 0, 100)));
    }

    /** Puts a run back into PROVISIONING as a pass that crashed mid-provision would have left it. */
    private static TestRun rewindToProvisioning(TestRun run) {
        return new TestRun(run.id(), run.teamId(), run.definitionVersionId(), run.executionPlanId(),
                RunState.PROVISIONING, run.environment(), run.trigger(), run.timeline(),
                run.cancellation(), run.failure(), run.claim(), run.synthetic(), run.lockVersion());
    }
}
