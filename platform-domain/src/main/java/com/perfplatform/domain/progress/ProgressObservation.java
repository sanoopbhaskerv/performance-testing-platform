package com.perfplatform.domain.progress;

import java.util.Objects;

/**
 * Something worth saying about a run that is still going, and the measurement it came from.
 *
 * <p>{@link #evidence} is not decoration. Every message this platform shows a person while they are
 * deciding whether to stop a test has to be traceable to a number they can check — otherwise it is
 * an opinion, and a tool that tells a novice something confident and wrong is worse than one that
 * shows a bare chart and says nothing.
 *
 * @param code     stable identifier, safe to branch on and to translate against
 * @param severity how loudly to say it
 * @param message  what it means, in a consumer's words
 * @param evidence the observation behind it, with the numbers in it
 */
public record ProgressObservation(
        String code,
        Severity severity,
        String message,
        String evidence) {

    /**
     * How much attention an observation deserves.
     *
     * <p>Three levels rather than five: this is read at a glance by someone who may be watching
     * their first load test, and a scale with more gradations than a person can act on differently
     * is a scale that gets ignored.
     */
    public enum Severity {
        /** Worth knowing. Nothing is wrong. */
        INFO,
        /** Worth looking at. The run can continue. */
        WARNING,
        /** Worth deciding about now. The run may be measuring nothing, or measuring a failure. */
        CRITICAL
    }

    public ProgressObservation {
        Objects.requireNonNull(severity, "severity");
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must not be null or blank");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must not be null or blank");
        }
        evidence = evidence == null ? "" : evidence;
    }
}
