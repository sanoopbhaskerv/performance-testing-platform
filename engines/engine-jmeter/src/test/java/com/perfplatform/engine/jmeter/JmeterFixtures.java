package com.perfplatform.engine.jmeter;

import com.perfplatform.domain.identity.ExecutionPlanId;
import com.perfplatform.domain.identity.TestDefinitionVersionId;
import com.perfplatform.domain.identity.TestRunId;
import com.perfplatform.domain.plan.ExecutionPlan;
import com.perfplatform.domain.spec.LoadModelType;
import com.perfplatform.domain.spec.PerformanceTestSpecification;
import com.perfplatform.domain.spec.PerformanceTestSpecification.DatasetSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.EnvironmentSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.LoadModel;
import com.perfplatform.domain.spec.PerformanceTestSpecification.LoadStage;
import com.perfplatform.domain.spec.PerformanceTestSpecification.ObservabilitySpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.ScenarioSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.SpecMetadata;
import com.perfplatform.domain.spec.PerformanceTestSpecification.TestSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.WorkloadSpec;
import com.perfplatform.domain.spec.SpecificationDigest;
import com.perfplatform.domain.spec.TargetClass;
import com.perfplatform.domain.spec.WorkloadType;
import com.perfplatform.domain.target.TargetAddress;
import com.perfplatform.engine.spi.EnginePlan;
import com.perfplatform.engine.spi.ShardAssignment;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Specifications for the JMeter engine's tests. */
final class JmeterFixtures {

    static final TargetAddress TARGET = TargetAddress.parse("https://proposal.example.internal");

    private JmeterFixtures() {
    }

    /** Two stages and three weighted scenarios — the shape V1 could not express. */
    static PerformanceTestSpecification twoStagesThreeScenarios() {
        return specification(List.of(
                workload("api-load", List.of(
                                new LoadStage(Duration.ofMinutes(5), 100),
                                new LoadStage(Duration.ofMinutes(10), 200)),
                        List.of(
                                new ScenarioSpec("search-client", 60, "searchClient"),
                                new ScenarioSpec("open-proposal", 30, "getProposal"),
                                new ScenarioSpec("save-proposal", 10, "saveProposal")))));
    }

    /** A single stage at the platform's stated maximum concurrency. */
    static PerformanceTestSpecification atMaximumConcurrency() {
        return specification(List.of(
                workload("api-load", List.of(new LoadStage(Duration.ofHours(1), 1_000)),
                        List.of(new ScenarioSpec("search-client", 100, "searchClient")))));
    }

    /** A workload whose requests draw their values from a dataset. */
    static PerformanceTestSpecification withDataset() {
        return specification(
                List.of(workload("api-load", List.of(new LoadStage(Duration.ofMinutes(5), 50)),
                        List.of(new ScenarioSpec("open-proposal", 100, null, "GET",
                                "/proposals/${proposalId}", Map.of(), Map.of("view", "${view}"),
                                null)))),
                List.of(new DatasetSpec("proposals",
                        new DatasetSpec.DataSource("object-storage", "dataset://proposals"),
                        List.of("proposalId", "view"), true)));
    }

    static PerformanceTestSpecification specification(List<WorkloadSpec> workloads) {
        return specification(workloads, List.of());
    }

    static PerformanceTestSpecification specification(List<WorkloadSpec> workloads,
            List<DatasetSpec> datasets) {
        return new PerformanceTestSpecification(
                PerformanceTestSpecification.SUPPORTED_API_VERSION,
                PerformanceTestSpecification.SUPPORTED_KIND,
                new SpecMetadata("proposal-baseline", "advice", "proposal", Map.of()),
                new TestSpec(
                        new EnvironmentSpec("performance", "advice-perf", TargetClass.NON_PRODUCTION),
                        List.of(), List.of(), datasets,
                        workloads,
                        List.of(),
                        new ObservabilitySpec("dynatrace", null, null, List.of()),
                        null));
    }

    static WorkloadSpec workload(String name, List<LoadStage> stages, List<ScenarioSpec> scenarios) {
        return new WorkloadSpec(name, WorkloadType.PROTOCOL, "jmeter", null,
                new LoadModel(LoadModelType.STAGES, stages), scenarios, null, null, null);
    }

    /** An open-model workload: a rate profile with the ceiling it cannot legally run without. */
    static WorkloadSpec arrivalRateWorkload(String name, int maxConcurrency, List<LoadStage> stages,
            List<ScenarioSpec> scenarios) {
        return new WorkloadSpec(name, WorkloadType.PROTOCOL, "jmeter", null,
                new LoadModel(LoadModelType.ARRIVAL_RATE, stages, maxConcurrency), scenarios,
                null, null, null);
    }

    static ShardAssignment shard(int index, int count) {
        return new ShardAssignment(TestRunId.generate(), index, count, null);
    }

    /**
     * A stored plan for the given specification, as the dispatcher would build it.
     *
     * <p>The first protocol workload's, since a plan describes one workload. A test needing a
     * particular one names it through {@link #planFor(PerformanceTestSpecification, String)}.
     */
    static ExecutionPlan planFor(PerformanceTestSpecification specification) {
        return planFor(specification, specification.spec().workloads().stream()
                .filter(workload -> workload.type() == WorkloadType.PROTOCOL)
                .map(PerformanceTestSpecification.WorkloadSpec::name)
                .findFirst()
                .orElseThrow());
    }

    static ExecutionPlan planFor(PerformanceTestSpecification specification, String workloadName) {
        EnginePlan compiled = JmeterPlanCompiler.compile(specification, workloadName);
        return new ExecutionPlan(
                ExecutionPlanId.generate(),
                TestDefinitionVersionId.generate(),
                workloadName,
                JmeterEngineProvider.ENGINE_ID,
                compiled.planJson(),
                SpecificationDigest.ofCanonicalForm(compiled.planJson()),
                new ExecutionPlan.CapacityEstimate(compiled.shardCount(),
                        compiled.peakVirtualUsers(), compiled.durationSeconds()),
                Instant.parse("2026-07-30T10:00:00Z"));
    }
}
