package com.perfplatform.domain.port;

import com.perfplatform.domain.identity.ExecutionPlanId;
import com.perfplatform.domain.plan.ExecutionPlan;

import com.perfplatform.domain.identity.TestRunId;

import java.util.List;
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
     * Every plan of a run, in workload order.
     *
     * <p>The authority on what a run executes. A run has one plan per workload, and asking the
     * store rather than carrying a list on the run keeps one source of truth: a set held in two
     * places diverges the first time a pass is interrupted between writing them.
     *
     * <p>Ordered by workload name so that two reads of the same run list its plans identically —
     * a report whose workloads reshuffled between refreshes would be unreadable.
     */
    List<ExecutionPlan> findByRun(TestRunId runId);

    /**
     * Persists a new plan and attaches it to a run.
     *
     * <p>Attaching here rather than in a second call is what makes provisioning idempotent: the
     * plan and the fact that this run owns it become durable together, so a pass interrupted
     * between them cannot leave a plan that no run claims and then compile a second one.
     *
     * @throws ImmutablePlanException if a plan with this identifier already exists, since a
     *                                rewritten plan would invalidate every result already verified
     *                                against it
     */
    ExecutionPlan save(TestRunId runId, ExecutionPlan plan);
}
