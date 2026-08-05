package com.perfplatform.orchestration.recorder;

import com.perfplatform.domain.recorder.RecorderAddressAllowances;
import com.perfplatform.domain.recorder.RecorderViewport;

import java.time.Duration;
import java.util.Objects;

/**
 * Deployment limits on the recorder bridge.
 *
 * <p>Every field here is a ceiling on something a user could otherwise consume without bound: how
 * long a browser lives, how much frame data the control plane holds for it, and whether the address
 * floor is relaxed. Production profiles refuse a relaxed floor
 * ({@link com.perfplatform.domain.recorder.RecorderAddressAllowances}).
 *
 * @param defaultViewport  the surface size a session gets when the client does not ask for one
 * @param idleTimeout      no brokered input for this long and the session expires
 * @param deadline         hard cap on session length regardless of activity
 * @param frameBufferSize  frames retained for a client that has fallen behind
 * @param eventBufferSize  blocked destinations and captures retained per session
 * @param allowances       the address floor this deployment applies
 */
public record RecorderBridgeSettings(
        RecorderViewport defaultViewport,
        Duration idleTimeout,
        Duration deadline,
        int frameBufferSize,
        int eventBufferSize,
        RecorderAddressAllowances allowances) {

    public RecorderBridgeSettings {
        Objects.requireNonNull(defaultViewport, "defaultViewport");
        requirePositive(idleTimeout, "idleTimeout");
        requirePositive(deadline, "deadline");
        if (deadline.compareTo(idleTimeout) < 0) {
            throw new IllegalArgumentException(
                    "Recorder deadline must be at least the idle timeout");
        }
        if (frameBufferSize < 1) {
            throw new IllegalArgumentException("Recorder frame buffer must retain at least 1 frame");
        }
        if (eventBufferSize < 1) {
            throw new IllegalArgumentException("Recorder event buffer must retain at least 1 event");
        }
        allowances = allowances == null ? RecorderAddressAllowances.strict() : allowances;
    }

    /** Defaults suitable for a shared deployment: strict floor, short session. */
    public static RecorderBridgeSettings defaults() {
        return new RecorderBridgeSettings(RecorderViewport.DEFAULT, Duration.ofMinutes(5),
                Duration.ofMinutes(30), 8, 500, RecorderAddressAllowances.strict());
    }

    private static void requirePositive(Duration value, String field) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("Recorder " + field + " must be positive");
        }
    }
}
