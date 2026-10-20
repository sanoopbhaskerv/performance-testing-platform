package com.perfplatform.orchestration.run;

import com.perfplatform.domain.admission.*;
import com.perfplatform.domain.execution.*;
import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.port.DefinitionVersionCatalog.DefinitionVersion;
import com.perfplatform.domain.run.*;
import com.perfplatform.domain.spec.*;
import com.perfplatform.domain.spec.PerformanceTestSpecification.*;
import com.perfplatform.orchestration.OrchestrationSettings;
import com.perfplatform.orchestration.plan.*;
import com.perfplatform.orchestration.support.InMemoryTestRunRepository;
import com.perfplatform.orchestration.support.OrchestrationFixtures;
import com.perfplatform.orchestration.support.OrchestrationFixtures.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Failure/restart and quota boundaries, without generating material load. */
class RunSafetyRegressionTest {
    final Instant start = Instant.parse("2026-10-20T00:00:00Z");
    final Clock clock = Clock.fixed(start, ZoneOffset.UTC);
    final TeamId team = TeamId.generate();
    final InMemoryTestRunRepository runs = new InMemoryTestRunRepository();
    final InMemoryShardRepository shards = new InMemoryShardRepository();
    final InMemoryPlanRepository plans = new InMemoryPlanRepository();
    final StubDefinitionVersionCatalog versions = new StubDefinitionVersionCatalog();
    final InMemoryRuntimeCredentialStore credentials = new InMemoryRuntimeCredentialStore();
    final InMemoryArtifacts artifacts = new InMemoryArtifacts();
    final InMemoryDatasets datasets = new InMemoryDatasets();
    final InMemoryProgress progress = new InMemoryProgress();
    final EngineRegistry engines = new EngineRegistry(List.of(new StubEngineProvider()));
    final SpyProvider provider = new SpyProvider();

    RunReconciler reconciler(Clock current, String owner, ExecutionLimits limits) {
        var workloads = new RunWorkloadService(engines, artifacts, artifacts,
                OrchestrationFixtures.permissiveAnalyzer(), OrchestrationFixtures.fixedTarget(),
                datasets, datasets, new RuntimeCredentialSessionPoolProvider(), progress);
        return new RunReconciler(runs, shards, plans, versions, new EngineDispatchingPlanCompiler(engines),
                provider, new ConcurrencyAdmissionPolicy(), new CapacityLimits(1, 1, Map.of()),
                OrchestrationSettings.DEFAULTS, credentials, workloads, limits, current, owner);
    }

    TestRun queue(PerformanceTestSpecification specification, String key) {
        DefinitionVersion version = versions.add(team, specification, true);
        return new TriggerTestRunUseCase(runs, versions, credentials, clock).trigger(
                new TriggerTestRunUseCase.TriggerCommand(version.id(), key), OrchestrationFixtures.caller(team)).run();
    }

    @Test
    void failureRetainsCapacityAndStopsAllHandlesAcrossRestartBeforeAdmittingNextRun() {
        var run = queue(specification(20, Duration.ofMinutes(1), 2), "broken");
        var next = queue(specification(20, Duration.ofMinutes(1), 1), "next");
        var reconciler = reconciler(clock, "before-restart", ExecutionLimits.DEFAULTS);
        reconciler.reconcile();
        reconciler.reconcile();
        var stopping = runs.find(run.id()).orElseThrow();
        assertThat(stopping.state()).isEqualTo(RunState.RUNNING);
        assertThat(stopping.failure().orElseThrow().code()).isEqualTo(RunFailureCode.RUNNER_EXITED_NON_ZERO);
        assertThat(stopping.timeline().completedAt()).isEmpty();
        assertThat(runs.currentCapacity().globalActive()).isEqualTo(1);
        assertThat(runs.find(next.id()).orElseThrow().state()).isEqualTo(RunState.QUEUED);
        assertThat(provider.cancelled).hasSize(2); // Includes the shard already labelled FAILED.
        assertThat(runs.events(run.id(), 0, 100)).extracting(RunEvent::type).contains(RunEventType.FAILURE_REQUESTED);

        provider.confirmStopped = true;
        reconciler(Clock.offset(clock, Duration.ofMinutes(3)), "after-restart", ExecutionLimits.DEFAULTS).reconcile();
        assertThat(runs.find(run.id()).orElseThrow().state()).isEqualTo(RunState.FAILED);
        assertThat(runs.find(run.id()).orElseThrow().failure().orElseThrow().code())
                .isEqualTo(RunFailureCode.RUNNER_EXITED_NON_ZERO);
        assertThat(runs.find(next.id()).orElseThrow().state()).isEqualTo(RunState.RUNNING);
        assertThat(provider.provisionCalls).isEqualTo(3); // Two originals and the newly admitted run.
    }

    @Test
    void provisioningRefusalStopsEarlierRunnersAndNeverResumesProvisioningAfterRestart() {
        provider.rejectSecond = true;
        var run = queue(specification(20, Duration.ofMinutes(1), 2), "refused");
        reconciler(clock, "first", ExecutionLimits.DEFAULTS).reconcile();
        assertThat(runs.find(run.id()).orElseThrow().state()).isEqualTo(RunState.PROVISIONING);
        assertThat(runs.find(run.id()).orElseThrow().failure().orElseThrow().code())
                .isEqualTo(RunFailureCode.PROVISIONING_FAILED);
        assertThat(runs.currentCapacity().globalActive()).isEqualTo(1);
        provider.confirmStopped = true;
        reconciler(Clock.offset(clock, Duration.ofMinutes(3)), "second", ExecutionLimits.DEFAULTS).reconcile();
        assertThat(runs.find(run.id()).orElseThrow().state()).isEqualTo(RunState.FAILED);
        assertThat(provider.provisionCalls).isEqualTo(2);
    }

    @Test
    void oversizedStoredDefinitionsCannotBeTriggered() {
        assertThatThrownBy(() -> queue(specification(20_000, Duration.ofHours(24), 1), "oversized"))
                .isInstanceOf(ExecutionLimits.ExceededException.class);
        assertThat(runs.findByTeam(team, 100)).isEmpty();
    }

    @Test
    void retryingAnExistingTriggerDoesNotCreateLoadWhenOperatorLimitsHaveChanged() {
        var original = queue(specification(100, Duration.ofMinutes(1), 1), "idempotent");
        var restricted = new TriggerTestRunUseCase(runs, versions, credentials, 20,
                new ExecutionLimits(50, Duration.ofMinutes(1)), clock);
        var replay = restricted.trigger(new TriggerTestRunUseCase.TriggerCommand(
                original.definitionVersionId(), "idempotent"), OrchestrationFixtures.caller(team));
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.run().id()).isEqualTo(original.id());
        assertThat(provider.provisionCalls).isZero();
    }

    @Test
    void queueIsRecheckedAgainstReducedOperatorLimitsBeforeAnyRunnerIsCreated() {
        var run = queue(specification(100, Duration.ofMinutes(10), 1), "queued-before-policy-change");
        reconciler(clock, "operator-restart", new ExecutionLimits(50, Duration.ofMinutes(5))).reconcile();
        assertThat(runs.find(run.id()).orElseThrow().state()).isEqualTo(RunState.FAILED);
        assertThat(provider.provisionCalls).isZero();
    }

    @Test
    void quotasAggregateAcrossWorkloadsWithoutIntegerOverflowAndPermitExactBoundaries() {
        assertThat(ExecutionLimits.DEFAULTS.validate(specification(Integer.MAX_VALUE, Duration.ofMinutes(1), 2)).codes())
                .contains("policy.protocolUsers.exceeded");
        assertThat(ExecutionLimits.DEFAULTS.validate(specification(500, Duration.ofHours(1), 2)).isValid()).isTrue();
        assertThat(ExecutionLimits.DEFAULTS.validate(specification(1, Duration.ofHours(2), 1)).codes())
                .contains("policy.duration.exceeded");
    }

    @Test
    void engineEstimatedDurationsCannotExceedOperatorLimitEvenWithoutAuthoredStages() {
        assertThatThrownBy(() -> ExecutionLimits.DEFAULTS.requireDuration(Duration.ofHours(2)))
                .isInstanceOf(ExecutionLimits.ExceededException.class);
        assertThatCode(() -> ExecutionLimits.DEFAULTS.requireDuration(Duration.ofHours(1))).doesNotThrowAnyException();
    }

    @Test
    void arrivalRateConcurrencyIsLimitedRatherThanConfusedWithRps() {
        var base = specification(1, Duration.ofMinutes(1), 1); var body = base.spec();
        var source = body.workloads().get(0);
        var arrival = new WorkloadSpec(source.name(), source.type(), source.engine(), null,
                new LoadModel(LoadModelType.ARRIVAL_RATE,
                        List.of(new LoadStage("steady", Duration.ofMinutes(1), 0, 100)), 1_001),
                source.scenarios(), null, null, null);
        var test = new PerformanceTestSpecification(base.apiVersion(), base.kind(), base.metadata(),
                new TestSpec(body.environment(), body.sources(), body.authentication(), body.datasets(),
                        List.of(arrival), body.objectives(), null, null));
        assertThat(ExecutionLimits.DEFAULTS.validate(test).codes()).contains("policy.protocolUsers.exceeded");
    }

    static PerformanceTestSpecification specification(int users, Duration duration, int count) {
        var base = OrchestrationFixtures.apiSpecification(); var body = base.spec();
        var workloads = new ArrayList<WorkloadSpec>();
        for (int i = 0; i < count; i++) {
            workloads.add(new WorkloadSpec("api-" + i, WorkloadType.PROTOCOL, "jmeter", null,
                    new LoadModel(LoadModelType.STAGES, List.of(new LoadStage(duration, users))),
                    body.workloads().get(0).scenarios(), null, null, null));
        }
        return new PerformanceTestSpecification(base.apiVersion(), base.kind(), base.metadata(),
                new TestSpec(body.environment(), body.sources(), body.authentication(), body.datasets(),
                        workloads, body.objectives(), body.observability(), null));
    }

    static class SpyProvider implements ExecutionProvider {
        int provisionCalls;
        boolean confirmStopped, rejectSecond;
        final List<RunnerShard.ExecutionHandle> handles = new ArrayList<>();
        final List<RunnerShard.ExecutionHandle> cancelled = new ArrayList<>();
        public String providerId() { return "controlled-test-provider"; }
        public boolean generatesRealLoad() { return true; }
        public ProvisioningResult provision(ProvisionRequest request) {
            provisionCalls++;
            if (rejectSecond && provisionCalls == 2) return ProvisioningResult.rejected("Fixture quota refusal");
            var handle = new RunnerShard.ExecutionHandle(providerId(), request.shardId().toString(), request.shardId().toString());
            handles.add(handle); return ProvisioningResult.accepted(handle);
        }
        public ExecutionStatus inspect(RunnerShard.ExecutionHandle handle) {
            return ExecutionStatus.of(handle.equals(handles.get(0)) ? ExecutionPhase.FAILED : ExecutionPhase.RUNNING);
        }
        public CancellationResult cancel(RunnerShard.ExecutionHandle handle) {
            cancelled.add(handle);
            return confirmStopped ? CancellationResult.terminated("Observed terminated") : CancellationResult.pending("Stopping");
        }
    }
}
