package com.perfplatform.domain.plan;

import com.perfplatform.domain.identity.ExecutionPlanId;
import com.perfplatform.domain.port.DefinitionVersionCatalog.DefinitionVersion;
import com.perfplatform.domain.spec.PerformanceTestSpecification;
import com.perfplatform.domain.spec.PerformanceTestSpecification.ScenarioSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.WorkloadSpec;
import com.perfplatform.domain.spec.SpecificationDigest;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * The engine-neutral plan compiler.
 *
 * <p>Produces the plan's identity, capacity estimate and workload structure without knowing what
 * any engine consumes. That is enough for everything Increment 1C does — admit the run, size its
 * deadline, provision shards and verify that returned results describe this plan — and it means the
 * digest contract is real from the first run rather than retrofitted.
 *
 * <p>Increment 1D adds the JMeter compiler behind {@link ExecutionPlanCompiler}. That changes what
 * {@link ExecutionPlan#planJson()} contains; it does not change how plans are identified, stored or
 * verified, which is why those are settled here.
 *
 * <h2>Why the JSON is hand-written</h2>
 * The same reason the specification's canonical form is (see {@code SpecificationCanonicalizer}):
 * the digest is stored, referenced by runs and checked against runner results, so it must not depend
 * on a serialization library's field ordering or number formatting. A library upgrade that changed
 * either would silently invalidate every stored plan. The output is deliberately plain: no
 * whitespace, keys in a fixed order, scenarios sorted by name.
 *
 * <h2>Sharding</h2>
 * One shard, always, in this increment. CLAUDE.md §15 requires shard count to come from a calibrated
 * capacity profile, and no calibration has been run — guessing a larger number would spread load
 * across generators whose per-pod ceiling nobody has measured, which is how a load test ends up
 * measuring its own generator.
 */
public final class BaselineExecutionPlanCompiler implements ExecutionPlanCompiler {

    /** Version tag on the emitted plan, so a later format change is distinguishable. */
    private static final String PLAN_VERSION = "v1alpha1";

    /** Until calibration determines otherwise (CLAUDE.md §15). */
    private static final int DEFAULT_SHARD_COUNT = 1;

    @Override
    public ExecutionPlan compile(ExecutionPlanId planId, DefinitionVersion version, Instant now) {
        PerformanceTestSpecification specification = version.specification();
        List<WorkloadSpec> workloads = workloadsOf(specification);
        if (workloads.isEmpty()) {
            throw new IllegalArgumentException(
                    "Version " + version.versionNumber() + " of definition " + version.definitionId()
                            + " declares no workloads and cannot be compiled");
        }

        String engine = primaryEngine(workloads);
        String planJson = renderPlan(specification, version, engine, workloads);

        return new ExecutionPlan(
                planId,
                version.id(),
                engine,
                planJson,
                SpecificationDigest.ofCanonicalForm(planJson),
                new ExecutionPlan.CapacityEstimate(
                        DEFAULT_SHARD_COUNT,
                        specification.peakProtocolUsers(),
                        totalDuration(workloads).toSeconds()),
                now);
    }

    private static List<WorkloadSpec> workloadsOf(PerformanceTestSpecification specification) {
        return specification.spec() == null ? List.of() : specification.spec().workloads();
    }

    /**
     * The engine the run is provisioned with.
     *
     * <p>The first declared workload's engine. A hybrid specification names more than one, and
     * coordinating them is Increment 3's problem; taking the first is honest about executing only
     * the protocol side today rather than silently dropping the browser workload without a record.
     */
    private static String primaryEngine(List<WorkloadSpec> workloads) {
        return workloads.stream()
                .map(WorkloadSpec::engine)
                .filter(engine -> engine != null && !engine.isBlank())
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No workload declares an engine, so no runner image can be selected"));
    }

    /**
     * Wall-clock time the run needs.
     *
     * <p>The longest workload, not the sum: workloads run concurrently, and summing would set a
     * deadline several times longer than the test, delaying detection of a run that has hung.
     */
    private static Duration totalDuration(List<WorkloadSpec> workloads) {
        return workloads.stream()
                .map(WorkloadSpec::totalDuration)
                .max(Comparator.naturalOrder())
                .orElse(Duration.ZERO);
    }

    private static String renderPlan(PerformanceTestSpecification specification,
            DefinitionVersion version, String engine, List<WorkloadSpec> workloads) {

        StringBuilder json = new StringBuilder(256);
        json.append('{');
        field(json, "planVersion", PLAN_VERSION).append(',');
        field(json, "engine", engine).append(',');
        field(json, "specificationDigest", version.digest().value()).append(',');
        field(json, "environment", environmentName(specification)).append(',');
        json.append("\"shardCount\":").append(DEFAULT_SHARD_COUNT).append(',');
        json.append("\"peakVirtualUsers\":").append(specification.peakProtocolUsers()).append(',');
        json.append("\"durationSeconds\":").append(totalDuration(workloads).toSeconds()).append(',');
        json.append("\"workloads\":[");

        for (int i = 0; i < workloads.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            renderWorkload(json, workloads.get(i));
        }

        return json.append("]}").toString();
    }

    private static void renderWorkload(StringBuilder json, WorkloadSpec workload) {
        json.append('{');
        field(json, "name", workload.name()).append(',');
        field(json, "type", workload.type() == null ? "" : workload.type().name()).append(',');
        field(json, "engine", workload.engine()).append(',');
        json.append("\"peakUsers\":").append(workload.peakUsers()).append(',');
        json.append("\"durationSeconds\":").append(workload.totalDuration().toSeconds()).append(',');

        // Scenario weights are part of the plan's identity: changing the traffic mix changes what
        // is being measured, so it must change the digest even when the endpoints are unchanged.
        // Sorted by name because declaration order is not meaningful and must not alter the digest.
        json.append("\"scenarios\":[");
        List<ScenarioSpec> scenarios = workload.scenarios().stream()
                .sorted(Comparator.comparing(ScenarioSpec::name,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();
        for (int i = 0; i < scenarios.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            ScenarioSpec scenario = scenarios.get(i);
            json.append('{');
            field(json, "name", scenario.name()).append(',');
            json.append("\"weight\":").append(scenario.weight());
            json.append('}');
        }
        json.append("]}");
    }

    private static String environmentName(PerformanceTestSpecification specification) {
        if (specification.spec() == null || specification.spec().environment() == null) {
            return "";
        }
        String name = specification.spec().environment().name();
        return name == null ? "" : name;
    }

    private static StringBuilder field(StringBuilder json, String key, String value) {
        return json.append('"').append(key).append("\":\"").append(escape(value)).append('"');
    }

    /**
     * JSON string escaping.
     *
     * <p>Handles the control characters as well as the obvious two. A workload name containing a
     * quote would otherwise emit a document that is not JSON, and one containing a newline would
     * produce a plan two different parsers disagree about — either of which turns a digest mismatch
     * into a mystery at the point results come back.
     */
    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                default -> {
                    if (c < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
