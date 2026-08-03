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
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

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

    @Override
    public ExecutionPlan compile(ExecutionPlanId planId, DefinitionVersion version, Instant now) {
        PerformanceTestSpecification specification = version.specification();
        String engineId = primaryEngine(specification);

        ExecutionEngineProvider engine = engines.require(engineId);

        EnginePlan compiled = engine.compile(specification);

        return new ExecutionPlan(
                planId,
                version.id(),
                engine.engineId(),
                compiled.planJson(),
                // The digest is over the engine's plan body. The platform computes it rather than
                // trusting the engine to, so an engine cannot claim an identity for a plan it did
                // not actually emit.
                SpecificationDigest.ofCanonicalForm(compiled.planJson()),
                new ExecutionPlan.CapacityEstimate(
                        compiled.shardCount(),
                        compiled.peakVirtualUsers(),
                        compiled.durationSeconds()),
                now);
    }

    /** The single engine this increment can compile; coordinated hybrid plans arrive later. */
    private static String primaryEngine(PerformanceTestSpecification specification) {
        if (specification.spec() == null) {
            throw new IllegalArgumentException("The specification has no body to compile");
        }
        Set<String> declared = specification.spec().workloads().stream()
                .map(WorkloadSpec::engine)
                .filter(engine -> engine != null && !engine.isBlank())
                .map(engine -> engine.trim().toLowerCase(java.util.Locale.ROOT))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (declared.isEmpty()) {
            throw new IllegalArgumentException(
                    "No workload declares an engine, so no runner image can be selected");
        }
        if (declared.size() > 1) {
            throw new IllegalArgumentException(
                    "This release compiles one engine per execution plan. Mixed-engine or hybrid "
                            + "specifications declared " + declared
                            + "; coordinated hybrid execution is deferred to S9.");
        }
        return declared.iterator().next();
    }
}
