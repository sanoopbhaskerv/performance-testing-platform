package com.perfplatform.domain.plan;

import com.perfplatform.domain.identity.ExecutionPlanId;
import com.perfplatform.domain.port.DefinitionVersionCatalog.DefinitionVersion;

import java.time.Instant;

/**
 * Turns an immutable specification version into the plan that will actually execute.
 *
 * <p>Compilation happens once, when a run is admitted, and the result is stored. It is not repeated
 * at execution time: shard count and stage translation depend on a calibration profile that changes
 * as runner sizing changes, and recompiling would let two runs of the same test quietly execute
 * differently.
 *
 * <p>Implementations must be deterministic. The same version compiled twice with the same profile
 * must produce byte-identical {@link ExecutionPlan#planJson()}, because its digest is the identity
 * runners are verified against — a compiler that emitted map entries in hash order would invalidate
 * results at random.
 */
public interface ExecutionPlanCompiler {

    /**
     * @param planId  identifier to assign to the compiled plan
     * @param version the immutable version to compile
     * @param now     creation instant
     * @throws IllegalArgumentException if the version cannot be compiled — no workloads, no engine
     */
    ExecutionPlan compile(ExecutionPlanId planId, DefinitionVersion version, Instant now);
}
