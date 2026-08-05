package com.perfplatform.domain.recorder;

import java.time.Instant;
import java.util.Objects;

/**
 * A destination the route guard refused, kept so the user can see why the page did not load.
 *
 * <p>A recorder that silently drops requests looks broken. Surfacing the refusal is what turns the
 * allowlist from a mystery into a thing an operator can widen deliberately, by declaring an asset
 * origin on the target, rather than by disabling the guard.
 *
 * @param destination the attempted URL, already reduced to origin and path by the caller
 * @param kind        which browser surface attempted it
 * @param code        stable {@code recorder.destination.*} or {@code recorder.address.*} code
 * @param reason      human-readable explanation
 */
public record RecorderBlockedDestination(
        long sequence,
        String destination,
        RecorderRequestKind kind,
        String code,
        String reason,
        Instant blockedAt) {

    public RecorderBlockedDestination {
        if (sequence < 1) {
            throw new IllegalArgumentException("Blocked-destination sequence must start at 1");
        }
        destination = Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(kind, "kind");
        code = Objects.requireNonNull(code, "code");
        reason = Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(blockedAt, "blockedAt");
    }

    public static RecorderBlockedDestination from(long sequence, String destination,
            RecorderRequestKind kind, RecorderDecision decision, Instant blockedAt) {
        Objects.requireNonNull(decision, "decision");
        if (decision.allowed()) {
            throw new IllegalArgumentException("An allowed decision is not a blocked destination");
        }
        return new RecorderBlockedDestination(sequence, destination, kind,
                decision.code(), decision.reason(), blockedAt);
    }
}
