package com.perfplatform.domain.run;

import com.perfplatform.domain.spec.support.SpecEnums;

/**
 * Why a run failed, as a stable code rather than a message.
 *
 * <p>The code exists so the reason can be shown, alerted on and aggregated without ever putting a
 * raw exception into a response. Runner failures carry Kubernetes object names, connection strings
 * and occasionally the contents of a request — none of which belongs in a consumer's CI log. The
 * code goes to the consumer; the detail goes to the platform's own logs behind a correlation id.
 *
 * <p>Codes are a public contract. Never rename one; add instead.
 */
public enum RunFailureCode {

    /** The runner could not be created — quota, image pull, admission webhook, scheduling. */
    PROVISIONING_FAILED,

    /** A shard exited non-zero. */
    RUNNER_EXITED_NON_ZERO,

    /** A shard stopped reporting and was declared lost. */
    RUNNER_LOST,

    /** The run exceeded its permitted wall-clock duration and was stopped. */
    DURATION_EXCEEDED,

    /**
     * Required shards produced no usable results. Distinct from a runner failure: the runner may
     * have exited cleanly having measured nothing, which must never be reported as a pass.
     */
    RESULTS_INCOMPLETE,

    /**
     * A runner returned results for a different execution plan than the one the run references.
     * Treated as a failure rather than merged, because the results describe a test nobody approved.
     */
    PLAN_DIGEST_MISMATCH,

    /**
     * The run executed cleanly but the system under test did not meet its objectives.
     *
     * <p>Distinct from every other code here: nothing went wrong with the <em>test</em>. It is a
     * failure of the thing being measured, which is the only conclusion anyone asked for, and
     * reporting it as a completed run would bury it.
     */
    OBJECTIVE_BREACHED,

    /** Analysis could not produce objective outcomes from the artifacts that were collected. */
    ANALYSIS_FAILED,

    /**
     * No load was generated, so the run measured nothing.
     *
     * <p>Produced by a development execution provider that invents its results. Its own code rather
     * than {@link #RESULTS_INCOMPLETE} because the two lead somewhere completely different: results
     * that arrived incomplete are an incident to investigate, while this is a deployment configured
     * with a provider that generates no traffic — a fact about the platform, not about the run.
     *
     * <p>A run reaching this state is the correct outcome, not a defect to route around. A provider
     * that fabricates measurements must never produce a run that reads as passing.
     */
    NO_LOAD_GENERATED,

    /** The control plane encountered an unexpected error it could not attribute. */
    INTERNAL_ERROR;

    public static RunFailureCode from(String raw) {
        return SpecEnums.parse(RunFailureCode.class, raw, "run failure code");
    }
}
