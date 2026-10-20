package com.perfplatform.domain.recorder;

import com.perfplatform.domain.spec.support.SpecEnums;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Lifecycle of one governed recording session.
 *
 * <p>Separate from {@code RunStatus} on purpose. A recording session is interactive and short-lived:
 * it is not queued, not admitted against run capacity and produces a draft rather than a
 * measurement. Sharing the run state machine would force both models to carry states the other
 * cannot reach.
 *
 * <p>{@link #PENDING_BROWSER_BRIDGE} is the state a session occupies between being audited and the
 * browser actually existing. Before the CDP bridge it was the only state a session ever had; it is
 * now a real waypoint, and a session that never leaves it means the bridge is disabled or refused
 * to start.
 */
public enum RecorderSessionState {

    /** Audited and authorized, but no browser is attached yet. */
    PENDING_BROWSER_BRIDGE,

    /** The bridge is launching a browser and freezing its route policy. */
    STARTING,

    /** A browser is attached and streaming; input is accepted. */
    LIVE,

    /** Shutdown requested; the browser is being torn down. */
    CLOSING,

    /** The session ended normally. */
    CLOSED,

    /** The bridge failed to start or died mid-session. */
    FAILED,

    /** A user or policy stopped the session. */
    CANCELLED,

    /** The session exceeded its idle timeout or deadline. */
    EXPIRED;

    private static final Map<RecorderSessionState, Set<RecorderSessionState>> ALLOWED = Map.of(
            PENDING_BROWSER_BRIDGE, EnumSet.of(STARTING, FAILED, CANCELLED, EXPIRED),
            STARTING, EnumSet.of(LIVE, FAILED, CANCELLED, EXPIRED),
            LIVE, EnumSet.of(CLOSING, FAILED, CANCELLED, EXPIRED),
            CLOSING, EnumSet.of(CLOSED, FAILED),
            CLOSED, EnumSet.noneOf(RecorderSessionState.class),
            FAILED, EnumSet.noneOf(RecorderSessionState.class),
            CANCELLED, EnumSet.noneOf(RecorderSessionState.class),
            EXPIRED, EnumSet.noneOf(RecorderSessionState.class));

    public boolean isTerminal() {
        return ALLOWED.get(this).isEmpty();
    }

    /** True while the session may still accept brokered input. */
    public boolean acceptsInput() {
        return this == LIVE;
    }

    public boolean canTransitionTo(RecorderSessionState next) {
        return next != null && ALLOWED.get(this).contains(next);
    }

    /**
     * @throws IllegalStateException if the transition is not in the table above
     */
    public RecorderSessionState transitionTo(RecorderSessionState next) {
        if (!canTransitionTo(next)) {
            throw new IllegalStateException(
                    "A recorder session cannot move from " + this + " to " + next + ".");
        }
        return next;
    }

    public static RecorderSessionState from(String raw) {
        return SpecEnums.parse(RecorderSessionState.class, raw, "recorder session state");
    }
}
