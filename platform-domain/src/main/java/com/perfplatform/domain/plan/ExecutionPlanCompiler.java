package com.perfplatform.domain.plan;

import com.perfplatform.domain.identity.ExecutionPlanId;
import com.perfplatform.domain.port.DefinitionVersionCatalog.DefinitionVersion;

import java.time.Instant;
import java.util.List;

/**
 * Turns an immutable specification version into the plans that will actually execute.
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
 *
 * <h2>One plan per workload</h2>
 * A specification declaring a protocol load and a browser cohort compiles to two plans for two
 * engines. Before S9 this returned a single plan and a multi-engine document was refused; before S6
 * its later workloads were silently dropped, so a run executed half the test and reported success.
 */
public interface ExecutionPlanCompiler {

    /**
     * Compiles every workload the version declares.
     *
     * @param planIds a source of identifiers, one taken per workload
     * @param version the immutable version to compile
     * @param now     creation instant
     * @return one plan per workload, in the order the document declares them. Order is meaningful:
     *         it is what the report lists, and what a later increment's start conditions resolve
     *         against
     * @throws IllegalArgumentException if the version cannot be compiled — no workloads, no engine,
     *                                  or an engine this deployment does not have
     */
    List<ExecutionPlan> compile(java.util.function.Supplier<ExecutionPlanId> planIds,
            DefinitionVersion version, Instant now);
}
