package com.perfplatform.domain.run;

import com.perfplatform.domain.spec.support.SpecEnums;

/**
 * The state of one runner shard.
 *
 * <p>Deliberately coarser than {@link RunState}. A shard is a unit of execution, not a governed
 * business object: it has no approval, no queue position and no admission decision. Reusing the run
 * states here would suggest a shard can be, say, {@code AWAITING_APPROVAL}, which is meaningless.
 */
public enum ShardState {

    /** Planned but not yet submitted to a runner provider. */
    PENDING,

    /** Submitted; the external handle is persisted. */
    PROVISIONED,

    /** Confirmed executing. */
    RUNNING,

    /** Finished successfully. */
    COMPLETED,

    /** Finished unsuccessfully, or was declared lost after its heartbeat went stale. */
    FAILED,

    /** Terminated on request. */
    CANCELLED;

    public static ShardState from(String raw) {
        return SpecEnums.parse(ShardState.class, raw, "shard state");
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
