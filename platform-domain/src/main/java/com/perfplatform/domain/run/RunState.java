package com.perfplatform.domain.run;

import com.perfplatform.domain.spec.support.SpecEnums;

/**
 * The durable state of a run.
 *
 * <h2>Why {@code DRAFT} is not here</h2>
 * CLAUDE.md §11 lists {@code DRAFT} among the platform states and asks for the distinction to be
 * documented if it belongs only to definitions. It does. A draft is a <em>specification</em> that
 * has not been versioned yet; it has no run, no execution plan and no capacity reservation. Once a
 * run exists, something has been asked to happen. Admitting a {@code DRAFT} run would create a row
 * that occupies a queue position while meaning "nothing was requested", and reconciliation would
 * have to special-case it at every step. Drafts live in the definition/version model instead.
 *
 * <h2>Active states hold capacity</h2>
 * {@link #PROVISIONING}, {@link #RUNNING} and {@link #ANALYZING} are the states that occupy a
 * concurrency slot. Analysis is included deliberately: a run being analyzed still owns its runner
 * artifacts and its shard records, and releasing its slot early would let the platform start an
 * eleventh material run while the tenth is still being reduced.
 */
public enum RunState {

    /** The submitted specification is being checked. Entry state for every run. */
    VALIDATING,

    /** Validation or policy refused the run. Terminal; no load was generated. */
    REJECTED,

    /** Waiting for a human approval that governance requires before any load is generated. */
    AWAITING_APPROVAL,

    /** Admitted to the queue, waiting for a free concurrency slot. */
    QUEUED,

    /** A runner is being created. External handles are persisted before leaving this state. */
    PROVISIONING,

    /** The runner is generating load. */
    RUNNING,

    /** Load has stopped; artifacts are being normalized and objectives evaluated. */
    ANALYZING,

    /** Finished, with results and objective outcomes. Terminal. */
    COMPLETED,

    /** Finished without usable results. Terminal. */
    FAILED,

    /** Stopped on request. Terminal. */
    CANCELLED,

    /** Timed out before it ever ran — an unapproved or unadmitted run that aged out. Terminal. */
    EXPIRED;

    public static RunState from(String raw) {
        return SpecEnums.parse(RunState.class, raw, "run state");
    }

    /**
     * Whether this state is final. A terminal run is never transitioned again by ordinary
     * operation; recovering one requires an explicit administrative workflow, so that a
     * reconciliation bug cannot quietly resurrect a finished run and replay its load.
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED
                || this == REJECTED || this == EXPIRED;
    }

    /**
     * Whether a run in this state occupies a concurrency slot.
     *
     * <p>This is the single definition used by admission control and by capacity reporting. Two
     * separate notions of "active" would drift, and the failure mode is over-admission — more
     * simultaneous load than the environment was sized for.
     */
    public boolean holdsCapacity() {
        return this == PROVISIONING || this == RUNNING || this == ANALYZING;
    }

    /**
     * Whether a runner may exist for a run in this state. Used by cancellation to decide whether
     * the provider must be asked to terminate something, or whether the run can be stopped outright.
     */
    public boolean mayHaveRunner() {
        return this == PROVISIONING || this == RUNNING;
    }
}
