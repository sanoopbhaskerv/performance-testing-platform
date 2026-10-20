package com.perfplatform.domain.run;

import com.perfplatform.domain.identity.TestRunId;

/**
 * Raised when a state change is not permitted by {@link RunStateMachine}.
 *
 * <p>An invalid transition is a defect, not a user error: it means two components disagree about
 * where a run is. It fails loudly rather than being applied, because the states this platform
 * guards are the ones that decide whether load is generated.
 */
public class InvalidRunTransitionException extends RuntimeException {

    private final transient RunState from;
    private final transient RunState to;

    public InvalidRunTransitionException(TestRunId runId, RunState from, RunState to) {
        super("Run " + runId + " cannot move from " + from + " to " + to
                + ". Permitted next states: " + RunStateMachine.nextStates(from));
        this.from = from;
        this.to = to;
    }

    public RunState from() {
        return from;
    }

    public RunState to() {
        return to;
    }
}
