package com.perfplatform.domain.admission;

/**
 * Whether a queued run may start now, and why not if it may not.
 *
 * <p>A deferral is not a rejection. The run keeps its queue position and is reconsidered next pass;
 * the reason exists so a consumer asking "why has my run not started" gets an answer that
 * distinguishes "the platform is full" from "your team already has one running" — which lead to
 * completely different actions.
 *
 * @param admitted whether the run may start
 * @param code     stable reason code, {@code null} when admitted
 * @param reason   short human-readable explanation, {@code null} when admitted
 */
public record AdmissionDecision(boolean admitted, String code, String reason) {

    /** Global concurrency ceiling reached. */
    public static final String GLOBAL_LIMIT = "capacity.global.exhausted";

    /** This team is already running as many runs as it is permitted. */
    public static final String TEAM_LIMIT = "capacity.team.exhausted";

    private static final AdmissionDecision ADMITTED = new AdmissionDecision(true, null, null);

    public static AdmissionDecision admit() {
        return ADMITTED;
    }

    public static AdmissionDecision defer(String code, String reason) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("A deferral must carry a reason code");
        }
        return new AdmissionDecision(false, code, reason);
    }

    public boolean isDeferred() {
        return !admitted;
    }
}
