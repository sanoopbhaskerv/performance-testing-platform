package com.perfplatform.domain.run;

import com.perfplatform.domain.spec.support.SpecEnums;

/**
 * What a {@link RunEvent} records.
 *
 * <p>These values are a public contract in the same way finding codes are: operators build queries
 * and alerts on them, so a name is never repurposed once released. New types may be added.
 *
 * <p>The set covers more than state changes on purpose. "Why did this run sit in the queue for
 * forty minutes" is not answerable from state transitions alone — the run was {@code QUEUED} the
 * whole time. {@link #ADMISSION_DEFERRED} and {@link #CLAIM_EXPIRED} record the decisions that
 * happened <em>while</em> the state stayed the same, which is where the answer usually is.
 */
public enum RunEventType {

    /** The run was created by a trigger. Always the first event. */
    REQUESTED,

    /** A duplicate trigger arrived with the same idempotency key and returned the existing run. */
    IDEMPOTENT_REPLAY,

    /** The run moved from one state to another. Carries both states. */
    STATE_CHANGED,

    /** Admission control declined to start the run this pass; it stays queued. */
    ADMISSION_DEFERRED,

    /** A reconciler took ownership of the run and holds a lease on it. */
    CLAIMED,

    /** A lease lapsed without being renewed; the run became claimable again. */
    CLAIM_EXPIRED,

    /** A runner shard was created and its external handle persisted. */
    SHARD_PROVISIONED,

    /** A workload was intentionally left pending until its start condition is satisfied. */
    WORKLOAD_DEFERRED,

    /** A delayed workload's start condition was satisfied and provisioning began. */
    WORKLOAD_START_ELIGIBLE,

    /** A shard reported a terminal outcome. */
    SHARD_FINISHED,

    /** A shard stopped reporting for longer than the configured heartbeat tolerance. */
    HEARTBEAT_STALE,

    /** Someone asked for the run to stop. Recorded when requested, not when it takes effect. */
    CANCELLATION_REQUESTED,

    /** Failure was decided; capacity remains reserved until every runner is confirmed stopped. */
    FAILURE_REQUESTED,

    /** The runner provider confirmed the workload is no longer executing. */
    EXECUTION_TERMINATED;

    public static RunEventType from(String raw) {
        return SpecEnums.parse(RunEventType.class, raw, "run event type");
    }
}
