package com.perfplatform.domain.port;

import com.perfplatform.domain.identity.ExecutionPlanId;
import com.perfplatform.domain.plan.ExecutionPlan;

import java.util.Optional;

/**
 * Durable storage for compiled execution plans.
 *
 * <p>Plans are written once and never updated. A run references a plan by identifier, and the
 * runner's results are checked against the plan's digest — both of which stop meaning anything if
 * the plan can be edited after the fact.
 */
public interface ExecutionPlanRepository {

    /**
     * Raised on any attempt to overwrite a stored plan.
     *
     * <p>Its own type rather than {@link IllegalStateException}, which Spring's persistence
     * exception translation rewrites into a data-access exception — the rule would then reach
     * callers wearing an unrelated type and be reported as an internal error rather than the
     * deliberate refusal it is.
     */
    class ImmutablePlanException extends RuntimeException {
        public ImmutablePlanException(ExecutionPlanId id) {
            super("Execution plan " + id + " already exists. Plans are immutable, because runner "
                    + "results are verified against their digest; compile a new plan instead.");
        }
    }

    Optional<ExecutionPlan> find(ExecutionPlanId id);

    /**
     * Persists a new plan.
     *
     * @throws ImmutablePlanException if a plan with this identifier already exists, since a
     *                                rewritten plan would invalidate every result already verified
     *                                against it
     */
    ExecutionPlan save(ExecutionPlan plan);
}
