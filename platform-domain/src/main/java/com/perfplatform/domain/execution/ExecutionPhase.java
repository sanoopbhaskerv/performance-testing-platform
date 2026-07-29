package com.perfplatform.domain.execution;

/**
 * What a runner provider observes about a workload it created.
 *
 * <p>Deliberately not the same enum as {@link com.perfplatform.domain.run.ShardState}. This is what
 * the provider <em>reports</em>; shard state is what the platform has durably <em>decided</em>.
 * Collapsing them would let an observation change stored state implicitly, and the two genuinely
 * differ: a provider reporting {@link #UNKNOWN} does not move a shard anywhere, and a shard the
 * platform has already cancelled stays cancelled however the provider later describes it.
 */
public enum ExecutionPhase {

    /** Accepted but not yet executing — scheduling, image pull, waiting for a node. */
    PENDING,

    /** Executing. */
    RUNNING,

    /** Finished successfully. */
    SUCCEEDED,

    /** Finished unsuccessfully. */
    FAILED,

    /**
     * The provider could not be reached or could not answer.
     *
     * <p>Distinct from {@link #NOT_FOUND}: an unreachable provider says nothing about the workload,
     * which may well still be generating load. Treating a query failure as a finished run would
     * free a capacity slot while the load continued.
     */
    UNKNOWN,

    /**
     * The provider has no record of the handle.
     *
     * <p>Either it was cleaned up after finishing, or it never existed. Both mean nothing is
     * executing now, which is the part that matters for capacity.
     */
    NOT_FOUND;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == NOT_FOUND;
    }
}
