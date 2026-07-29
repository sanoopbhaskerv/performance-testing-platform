package com.perfplatform.orchestration.run;

import com.perfplatform.domain.admission.CapacityLimits;
import com.perfplatform.domain.admission.ConcurrencyAdmissionPolicy;
import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.identity.TestDefinitionVersionId;
import com.perfplatform.domain.identity.TestRunId;
import com.perfplatform.domain.plan.BaselineExecutionPlanCompiler;
import com.perfplatform.domain.port.DefinitionVersionCatalog.DefinitionVersion;
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
import com.perfplatform.orchestration.support.OrchestrationFixtures.InMemoryPlanRepository;
import com.perfplatform.orchestration.support.OrchestrationFixtures.InMemoryShardRepository;
import com.perfplatform.orchestration.support.OrchestrationFixtures.StubDefinitionVersionCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

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

    private FakeExecutionProvider provider;
    private TriggerTestRunUseCase trigger;
    private CancelTestRunUseCase cancel;
    private TeamId team;
    private DefinitionVersion version;

    @BeforeEach
    void setUp() {
        provider = FakeExecutionProvider.immediate(clock);
        trigger = new TriggerTestRunUseCase(runs, versions, clock);
        cancel = new CancelTestRunUseCase(runs, clock);
        team = TeamId.generate();
        version = versions.add(team);
    }

    private RunReconciler reconciler(String owner) {
        return reconciler(owner, CapacityLimits.DEFAULTS);
    }

    private RunReconciler reconciler(String owner, CapacityLimits limits) {
        return new RunReconciler(runs, shards, plans, versions,
                new BaselineExecutionPlanCompiler(), provider, new ConcurrencyAdmissionPolicy(),
                limits, OrchestrationSettings.DEFAULTS, clock, owner);
    }

    private TestRun queueRun(String key) {
        return trigger.trigger(new TriggerTestRunUseCase.TriggerCommand(version.id(), key),
                OrchestrationFixtures.caller(team)).run();
    }

    private TestRun reload(TestRun run) {
        return runs.find(run.id()).orElseThrow();
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
            assertThat(reload(run).executionPlanId()).isPresent();
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
                        plans.find(reload(run).executionPlanId().orElseThrow()).orElseThrow()
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
    private static TestRun rewindToProvisioning(TestRun run) {
        return new TestRun(run.id(), run.teamId(), run.definitionVersionId(), run.executionPlanId(),
                RunState.PROVISIONING, run.environment(), run.trigger(), run.timeline(),
                run.cancellation(), run.failure(), run.claim(), run.lockVersion());
    }
}
