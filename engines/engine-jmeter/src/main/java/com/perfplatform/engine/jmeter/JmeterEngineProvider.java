package com.perfplatform.engine.jmeter;

import com.perfplatform.domain.plan.ExecutionPlan;
import com.perfplatform.domain.results.SampleSet;
import com.perfplatform.domain.spec.PerformanceTestSpecification;
import com.perfplatform.domain.spec.PerformanceTestSpecification.WorkloadSpec;
import com.perfplatform.domain.spec.WorkloadType;
import com.perfplatform.domain.target.TargetAddress;
import com.perfplatform.domain.validation.ValidationFinding;
import com.perfplatform.domain.validation.ValidationResult;
import com.perfplatform.engine.spi.EngineCapabilities;
import com.perfplatform.engine.spi.EnginePlan;
import com.perfplatform.engine.spi.ExecutionEngineProvider;
import com.perfplatform.engine.spi.RenderedWorkload;
import com.perfplatform.engine.spi.RunnerOutput;
import com.perfplatform.engine.spi.ShardAssignment;
import com.perfplatform.engine.spi.WorkloadKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * JMeter provider for the engine SPI.
 *
 * <p>Increment 1A declares JMeter's identity and capabilities so orchestration can reason about
 * it through the SPI. Plan compilation, sharding and result normalization move here from
 * {@code JmeterTestOrchestrator} in Increment 1D, at which point the compiler defects recorded in
 * {@code docs/migration/baseline-report.md} (numeric corruption at values of 1,000 or more, the
 * missing spike template, endurance reusing load scenarios, and scenario flattening) are fixed.
 *
 * <p>This class holds no Spring annotations. It is registered as a bean by the composition root
 * in {@code platform-core}, keeping the engine module free of the framework.
 */
public final class JmeterEngineProvider implements ExecutionEngineProvider {

    /** Stable identifier persisted on execution plans and runner shards. */
    public static final String ENGINE_ID = "jmeter";

    /**
     * Pinned runner image. Never {@code latest}: two shards of one run must execute the same
     * engine build, or the merged result is not reproducible from the stored plan digest.
     * The registry prefix becomes an approved internal registry before production use.
     */
    private static final String RUNNER_IMAGE = "justb4/jmeter:5.6.2";

    /**
     * Per-shard ceiling. This is a policy limit, not a measured one. The real figure comes from
     * runner calibration against a representative workload; virtual-user count alone is not a
     * reliable CPU-sizing measure. Until calibration is complete this bounds a single shard well
     * below the 1,000 virtual-user per-run maximum, so a run at the ceiling shards rather than
     * overloading one generator.
     */
    private static final int MAX_CONFIGURED_CONCURRENCY_PER_SHARD = 500;

    private static final EngineCapabilities CAPABILITIES = new EngineCapabilities(
            ENGINE_ID,
            Set.of(WorkloadKind.PROTOCOL),
            Set.of("openapi", "postman", "har", "curl", "manual"),
            Set.of("stages"),
            MAX_CONFIGURED_CONCURRENCY_PER_SHARD,
            true,
            true,
            RUNNER_IMAGE);

    @Override
    public String engineId() {
        return ENGINE_ID;
    }

    @Override
    public EngineCapabilities capabilities() {
        return CAPABILITIES;
    }

    /**
     * Engine-capability validation.
     *
     * <p>Only what JMeter can answer about itself. Whether the document is well formed, whether the
     * target is permitted and whether the team has quota were all decided before this is called;
     * repeating those checks here would put the same rule in two places, and the two would drift.
     */
    @Override
    public ValidationResult validate(PerformanceTestSpecification specification) {
        List<ValidationFinding> findings = new ArrayList<>();

        List<WorkloadSpec> mine = JmeterPlanCompiler.protocolWorkloads(specification);
        if (mine.isEmpty()) {
            findings.add(ValidationFinding.error("engine.jmeter.noWorkload", "/spec/workloads",
                    "No protocol workload is assigned to the JMeter engine.",
                    "Set engine: jmeter on at least one protocol workload."));
            return ValidationResult.of(findings);
        }

        for (int i = 0; i < mine.size(); i++) {
            WorkloadSpec workload = mine.get(i);
            String path = "/spec/workloads/" + i;

            if (workload.model() == null || workload.model().type() == null) {
                findings.add(ValidationFinding.error("engine.jmeter.noLoadModel", path + "/model",
                        "Workload '" + workload.name() + "' declares no load model.",
                        "Add a staged model describing how load changes over time."));
            } else {
                String model = workload.model().type().name().toLowerCase(Locale.ROOT);
                if (!CAPABILITIES.supportedWorkloadModels().contains(model)) {
                    findings.add(ValidationFinding.error("engine.jmeter.unsupportedLoadModel",
                            path + "/model/type",
                            "JMeter does not implement the '" + model + "' load model.",
                            "Supported models: "
                                    + String.join(", ", CAPABILITIES.supportedWorkloadModels())));
                }
            }

            // The per-shard ceiling, not the per-run one. Exceeding it is not an error: it is what
            // sharding exists for, and the capacity profile decides how many shards to use. It is
            // reported so the decision is visible rather than silent.
            if (workload.peakUsers() > CAPABILITIES.maxConfiguredConcurrency()) {
                findings.add(ValidationFinding.warning("engine.jmeter.shardingRequired",
                        path + "/model",
                        "Workload '" + workload.name() + "' peaks at " + workload.peakUsers()
                                + " users, above the configured per-shard ceiling of "
                                + CAPABILITIES.maxConfiguredConcurrency()
                                + ". It will be split across shards.",
                        "No action needed. Shard count comes from the calibrated capacity profile."));
            }

            if (workload.scenarios().isEmpty()) {
                findings.add(ValidationFinding.error("engine.jmeter.noScenarios", path + "/scenarios",
                        "Workload '" + workload.name() + "' declares no scenarios, so there is "
                                + "nothing to execute.",
                        "Add at least one weighted scenario."));
            }
        }

        // A browser workload in the same specification is not an error — a hybrid test is a
        // supported shape — but JMeter will not run it, and saying so avoids the assumption that
        // triggering this specification exercises the browser journeys too.
        if (!specification.workloadsOfType(WorkloadType.BROWSER).isEmpty()) {
            findings.add(ValidationFinding.info("engine.jmeter.browserWorkloadIgnored",
                    "/spec/workloads",
                    "This specification declares browser workloads. JMeter will not execute them; "
                            + "browser execution arrives with the Playwright engine."));
        }

        return ValidationResult.of(findings);
    }

    @Override
    public EnginePlan compile(PerformanceTestSpecification specification, String workloadName) {
        return JmeterPlanCompiler.compile(specification, workloadName);
    }

    @Override
    public RenderedWorkload render(PerformanceTestSpecification specification, ExecutionPlan plan,
            ShardAssignment assignment, TargetAddress target) {
        return JmeterWorkloadRenderer.render(specification, plan, assignment, target);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Delegates to the renderer, which is the thing that writes the name into the plan. The
     * alternative — orchestration deciding a name and the renderer writing another — is a run whose
     * data file is present under a name JMeter never looks for, and every {@code ${column}} sent as
     * literal text.
     */
    @Override
    public String datasetFileName(String datasetName) {
        return JmeterWorkloadRenderer.partitionFileName(datasetName);
    }

    @Override
    public SampleSet parse(RunnerOutput output) {
        SampleSet combined = SampleSet.empty();
        for (RunnerOutput.ShardResult shard : output.shardResults()) {
            combined = combined.merge(JtlParser.parse(shard.resultData()));
        }
        return combined;
    }
}
