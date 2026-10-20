package com.perfplatform.engine.jmeter;

import com.perfplatform.domain.spec.PerformanceTestSpecification;
import com.perfplatform.domain.spec.PerformanceTestSpecification.LoadStage;
import com.perfplatform.domain.spec.PerformanceTestSpecification.ScenarioSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.WorkloadSpec;
import com.perfplatform.domain.spec.WorkloadType;
import com.perfplatform.domain.spec.support.CanonicalJson;
import com.perfplatform.engine.spi.EnginePlan;
import com.perfplatform.engine.spi.ShardAssignment;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;

/**
 * Compiles a specification into JMeter's execution plan.
 *
 * <p>The plan is the reviewable, digest-verified description of what will run. It preserves
 * everything V1's compiler discarded:
 *
 * <ul>
 *   <li><b>Scenario boundaries and weights</b> (defect D4). V1 flattened every scenario into one
 *       endpoint list, so a realistic traffic mix could not be expressed and scenario names did not
 *       appear in the generated plan at all.</li>
 *   <li><b>Workload stages.</b> V1 had a single ramp and steady state taken from team config; a
 *       staged model could not be represented.</li>
 *   <li><b>The requested test's own scenarios</b> (defect D3). V1 always read the <em>load</em>
 *       test's scenarios whatever type was requested, so an endurance run silently executed the
 *       load test's endpoints.</li>
 * </ul>
 *
 * <p>Deterministic: the same specification always compiles to byte-identical JSON. Scenarios are
 * ordered by name and workloads in declaration order, and numbers are written as numbers.
 */
final class JmeterPlanCompiler {

    /** Version tag on the emitted plan, so a later format change is distinguishable. */
    private static final String PLAN_VERSION = "v1alpha1";

    /**
     * Shards per run, until calibration says otherwise.
     *
     * <p>CLAUDE.md §15 requires shard count to come from a calibrated capacity profile. None has
     * been run, and guessing a larger number spreads load across generators whose per-pod ceiling
     * nobody has measured — which is how a load test ends up measuring its own generator.
     */
    private static final int DEFAULT_SHARD_COUNT = 1;

    private JmeterPlanCompiler() {
    }

    static EnginePlan compile(PerformanceTestSpecification specification, String workloadName) {
        List<WorkloadSpec> workloads = protocolWorkloads(specification).stream()
                .filter(workload -> workloadName == null
                        || workloadName.equals(workload.name()))
                .toList();
        if (workloads.isEmpty()) {
            throw new IllegalArgumentException(
                    "This specification declares no JMeter protocol workload named '"
                            + workloadName + "', so there is nothing to compile");
        }

        CanonicalJson json = new CanonicalJson()
                .startObject()
                .field("planVersion", PLAN_VERSION)
                .field("engine", JmeterEngineProvider.ENGINE_ID)
                .field("environment", environmentName(specification))
                .field("correlationHeader", correlationHeader(specification))
                .field("shardCount", DEFAULT_SHARD_COUNT)
                // The concurrency the run reserves, which for an open model is its ceiling rather
                // than its rate. Named for what it is so a plan reader is never left inferring it.
                .field("peakVirtualUsers", specification.peakProtocolUsers())
                .field("durationSeconds", totalDuration(workloads).toSeconds());

        json.startArray("workloads");
        for (WorkloadSpec workload : workloads) {
            writeWorkload(json, workload);
        }
        json.endArray().endObject();

        return new EnginePlan(json.build(), DEFAULT_SHARD_COUNT,
                specification.peakProtocolUsers(), totalDuration(workloads).toSeconds());
    }

    private static void writeWorkload(CanonicalJson json, WorkloadSpec workload) {
        boolean arrivalRate = workload.model() != null && workload.model().isArrivalRate();

        json.startElement()
                .field("name", workload.name())
                // Which quantity this workload's stages are expressed in. A plan that did not say
                // would leave a reader unable to tell 400 users from 400 requests a second, and the
                // two describe wildly different tests against the same numbers.
                .field("model", arrivalRate ? "arrival-rate" : "stages")
                .field("peakUsers", workload.peakUsers())
                .field("peakArrivalRate", workload.peakArrivalRate())
                .field("durationSeconds", workload.totalDuration().toSeconds());

        // Stages in declaration order, which is meaningful here: a staged model is a sequence, and
        // reordering it describes a different test.
        json.startArray("stages");
        for (LoadStage stage : stages(workload)) {
            json.startElement()
                    .field("durationSeconds",
                            stage.duration() == null ? 0 : stage.duration().toSeconds())
                    .field("targetUsers", stage.targetUsers())
                    .field("targetRate", stage.targetRate() == null ? 0 : stage.targetRate())
                    .endObject();
        }
        json.endArray();

        // Scenarios sorted by name: declaration order is not meaningful for a weighted mix, and
        // letting it affect the digest would make two identical tests look different.
        json.startArray("scenarios");
        for (ScenarioSpec scenario : sortedScenarios(workload)) {
            json.startElement()
                    .field("name", scenario.name())
                    .field("weight", scenario.weight())
                    .field("operationId", scenario.operationId())
                    .endObject();
        }
        json.endArray();

        json.endObject();
    }

    /**
     * Protocol workloads only.
     *
     * <p>A hybrid specification also declares browser workloads, and JMeter does not run those.
     * Silently including them would produce a plan claiming to execute journeys it cannot; the
     * Playwright engine takes them in Increment 3.
     */
    static List<WorkloadSpec> protocolWorkloads(PerformanceTestSpecification specification) {
        return specification.workloadsOfType(WorkloadType.PROTOCOL).stream()
                .filter(workload -> JmeterEngineProvider.ENGINE_ID.equalsIgnoreCase(workload.engine()))
                .toList();
    }

    static List<LoadStage> stages(WorkloadSpec workload) {
        if (workload.model() == null || workload.model().stages() == null) {
            return List.of();
        }
        return workload.model().stages().stream()
                .filter(stage -> stage != null && stage.duration() != null)
                .toList();
    }

    static List<ScenarioSpec> sortedScenarios(WorkloadSpec workload) {
        return workload.scenarios().stream()
                .sorted(Comparator.comparing(ScenarioSpec::name,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();
    }

    /**
     * Wall-clock time the run needs: the longest workload, not the sum.
     *
     * <p>Workloads run concurrently. Summing would set a deadline several times longer than the
     * test and delay detection of a run that has hung.
     */
    static Duration totalDuration(List<WorkloadSpec> workloads) {
        return workloads.stream()
                .map(WorkloadSpec::totalDuration)
                .max(Comparator.naturalOrder())
                .orElse(Duration.ZERO);
    }

    static String correlationHeader(PerformanceTestSpecification specification) {
        if (specification.spec() == null || specification.spec().observability() == null) {
            return ShardAssignment.DEFAULT_CORRELATION_HEADER;
        }
        String header = specification.spec().observability().correlationHeader();
        return header == null || header.isBlank()
                ? ShardAssignment.DEFAULT_CORRELATION_HEADER : header;
    }

    private static String environmentName(PerformanceTestSpecification specification) {
        if (specification.spec() == null || specification.spec().environment() == null) {
            return "";
        }
        String name = specification.spec().environment().name();
        return name == null ? "" : name;
    }
}
