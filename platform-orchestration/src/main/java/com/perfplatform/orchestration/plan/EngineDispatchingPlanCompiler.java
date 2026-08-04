package com.perfplatform.orchestration.plan;

import com.perfplatform.domain.identity.ExecutionPlanId;
import com.perfplatform.domain.plan.ExecutionPlan;
import com.perfplatform.domain.plan.ExecutionPlanCompiler;
import com.perfplatform.domain.port.DefinitionVersionCatalog.DefinitionVersion;
import com.perfplatform.domain.spec.PerformanceTestSpecification;
import com.perfplatform.domain.spec.PerformanceTestSpecification.WorkloadSpec;
import com.perfplatform.domain.spec.SpecificationDigest;
import com.perfplatform.engine.spi.EnginePlan;
import com.perfplatform.engine.spi.ExecutionEngineProvider;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Compiles a specification by handing it to the engine it names.
 *
 * <p>This replaces the placeholder compiler Increment 1C shipped. That one produced an
 * engine-neutral envelope so the digest and storage contracts could be settled before any engine
 * existed; now the engine produces the plan body and the platform still owns everything around it —
 * identity, digest, capacity estimate, creation time.
 *
 * <p>Orchestration never branches on which engine it is. It looks the engine up by identifier and
 * calls the SPI, which is the whole point of having an SPI: adding an engine adds a provider bean
 * and changes nothing here.
 */
public final class EngineDispatchingPlanCompiler implements ExecutionPlanCompiler {

    private final EngineRegistry engines;

    public EngineDispatchingPlanCompiler(EngineRegistry engines) {
        this.engines = Objects.requireNonNull(engines, "engines");
    }

    /**
     * {@inheritDoc}
     *
     * <p>One plan per workload, in declaration order, each compiled by the engine its own workload
     * names. Nothing here looks at what the engines are: a hybrid specification is compiled by
     * asking two providers the same question about different workloads.
     *
     * <p>A workload naming an engine this deployment does not have fails here loudly rather than
     * being skipped — but it should never reach here, because the engine-capability validation layer
     * refuses such a document before it can be stored as a version.
     */
    @Override
    public List<ExecutionPlan> compile(Supplier<ExecutionPlanId> planIds, DefinitionVersion version,
            Instant now) {

        PerformanceTestSpecification specification = version.specification();
        if (specification.spec() == null) {
            throw new IllegalArgumentException("The specification has no body to compile");
        }

        List<WorkloadSpec> workloads = specification.spec().workloads();
        if (workloads.isEmpty()) {
            throw new IllegalArgumentException(
                    "This specification declares no workload, so there is nothing to compile");
        }

        List<ExecutionPlan> plans = new ArrayList<>(workloads.size());
        Set<String> seen = new LinkedHashSet<>();

        for (WorkloadSpec workload : workloads) {
            if (workload.name() == null || workload.name().isBlank()) {
                throw new IllegalArgumentException(
                        "A workload with no name cannot be compiled: its plan could not be told "
                                + "apart from the run's other plans");
            }
            // Duplicate names are already a semantic validation error. Repeated here because this
            // is the point where two plans would collide on the run's unique key, and a constraint
            // violation deep in provisioning is a far worse way to learn about it.
            if (!seen.add(workload.name())) {
                throw new IllegalArgumentException(
                        "Two workloads are both named '" + workload.name()
                                + "', so their plans could not be told apart");
            }
            if (workload.engine() == null || workload.engine().isBlank()) {
                throw new IllegalArgumentException("Workload '" + workload.name()
                        + "' declares no engine, so no runner image can be selected");
            }

            ExecutionEngineProvider engine = engines.require(workload.engine());
            EnginePlan compiled = engine.compile(specification, workload.name());

            plans.add(new ExecutionPlan(
                    planIds.get(),
                    version.id(),
                    workload.name(),
                    engine.engineId(),
                    compiled.planJson(),
                    // The digest is over the engine's plan body. The platform computes it rather
                    // than trusting the engine to, so an engine cannot claim an identity for a plan
                    // it did not actually emit.
                    SpecificationDigest.ofCanonicalForm(compiled.planJson()),
                    new ExecutionPlan.CapacityEstimate(
                            compiled.shardCount(),
                            compiled.peakVirtualUsers(),
                            compiled.durationSeconds()),
                    now));
        }

        return List.copyOf(plans);
    }
}
