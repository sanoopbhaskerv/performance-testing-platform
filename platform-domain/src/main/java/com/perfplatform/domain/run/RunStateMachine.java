package com.perfplatform.domain.run;

import com.perfplatform.domain.identity.TestRunId;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static com.perfplatform.domain.run.RunState.ANALYZING;
import static com.perfplatform.domain.run.RunState.AWAITING_APPROVAL;
import static com.perfplatform.domain.run.RunState.CANCELLED;
import static com.perfplatform.domain.run.RunState.COMPLETED;
import static com.perfplatform.domain.run.RunState.EXPIRED;
import static com.perfplatform.domain.run.RunState.FAILED;
import static com.perfplatform.domain.run.RunState.PROVISIONING;
import static com.perfplatform.domain.run.RunState.QUEUED;
import static com.perfplatform.domain.run.RunState.REJECTED;
import static com.perfplatform.domain.run.RunState.RUNNING;
import static com.perfplatform.domain.run.RunState.VALIDATING;

/**
 * The one place a run state change is judged legal.
 *
 * <p>V1's defect was not that its transitions were wrong but that there were none: status was a
 * field any code path could assign. Centralizing the matrix here means a new caller — a reconciler,
 * an admin recovery tool, a future runner agent — cannot invent a path that skips provisioning or
 * revives a finished run, and the rules can be read in one place rather than inferred from every
 * assignment site.
 *
 * <p>The matrix is deliberately conservative in two ways:
 *
 * <ul>
 *   <li><b>No transition out of a terminal state.</b> A run that reports {@code COMPLETED} has had
 *       its results published and its objectives evaluated; moving it again would invalidate what
 *       consumers already read. Administrative recovery is a separate, explicit workflow.</li>
 *   <li><b>No {@code QUEUED → RUNNING} shortcut.</b> Every run passes through
 *       {@link RunState#PROVISIONING} so that the external runner handle is persisted <em>before</em>
 *       the platform believes load is being generated. Without that ordering a control-plane crash
 *       between the two leaves an orphaned runner that nothing knows how to cancel.</li>
 * </ul>
 */
public final class RunStateMachine {

    private static final Map<RunState, Set<RunState>> ALLOWED = allowedTransitions();

    private RunStateMachine() {
    }

    private static Map<RunState, Set<RunState>> allowedTransitions() {
        Map<RunState, Set<RunState>> allowed = new EnumMap<>(RunState.class);

        // Validation decides between refusal, approval, and the queue. Cancellation is available
        // from the first moment a run exists: a consumer who mistypes a target should not have to
        // wait for it to be admitted before stopping it.
        allowed.put(VALIDATING, EnumSet.of(REJECTED, AWAITING_APPROVAL, QUEUED, CANCELLED));

        // EXPIRED is reachable here and from QUEUED because both are waiting states. An approval
        // nobody grants, or a slot that never frees, must not leave a run pending forever.
        allowed.put(AWAITING_APPROVAL, EnumSet.of(QUEUED, REJECTED, CANCELLED, EXPIRED));
        allowed.put(QUEUED, EnumSet.of(PROVISIONING, CANCELLED, EXPIRED));

        // FAILED is reachable from PROVISIONING because runner creation itself can fail.
        allowed.put(PROVISIONING, EnumSet.of(RUNNING, FAILED, CANCELLED));
        allowed.put(RUNNING, EnumSet.of(ANALYZING, FAILED, CANCELLED));

        // Analysis cannot be cancelled: the load has already stopped, so there is nothing left to
        // stop, and discarding a run's results at this point loses evidence that was paid for.
        allowed.put(ANALYZING, EnumSet.of(COMPLETED, FAILED));

        for (RunState terminal : RunState.values()) {
            if (terminal.isTerminal()) {
                allowed.put(terminal, EnumSet.noneOf(RunState.class));
            }
        }
        return Map.copyOf(allowed);
    }

    /** The states a run may move to next. Empty for terminal states. */
    public static Set<RunState> nextStates(RunState from) {
        return ALLOWED.getOrDefault(from, EnumSet.noneOf(RunState.class));
    }

    /**
     * Whether the transition is permitted.
     *
     * <p>A transition to the same state is <em>not</em> permitted. Re-entering a state would append
     * a second event claiming a change that did not happen, which corrupts the audit trail that
     * makes a run reconstructable. Idempotent callers check {@link #isIdempotentRepeat} first.
     */
    public static boolean canTransition(RunState from, RunState to) {
        return from != null && to != null && nextStates(from).contains(to);
    }

    /**
     * Whether a caller asking for {@code to} is repeating a transition that already happened,
     * rather than requesting an illegal one.
     *
     * <p>This is what lets cancellation be safely retried: a second cancel of an already-cancelled
     * run is a repeat, not a violation, and must not raise.
     */
    public static boolean isIdempotentRepeat(RunState current, RunState to) {
        return current == to;
    }

    /**
     * Validates a transition, raising if it is not permitted.
     *
     * @throws InvalidRunTransitionException if the transition is not in the matrix
     */
    public static void requireTransition(TestRunId runId, RunState from, RunState to) {
        if (!canTransition(from, to)) {
            throw new InvalidRunTransitionException(runId, from, to);
        }
    }
}
