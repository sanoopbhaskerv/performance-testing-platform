package com.perfplatform.domain.identity;

import java.util.UUID;

/**
 * Marker for a strongly typed, externally visible resource identifier.
 *
 * <p>Every aggregate in the domain is identified by its own type rather than by a bare
 * {@code String}. Passing arbitrary strings around lets a team identifier be used where a run
 * identifier is expected — a mistake the compiler cannot catch and that authorization code in
 * particular must never make.
 *
 * <p>Identifiers are UUID-backed because they are externally visible: they appear in URLs, in
 * artifact object keys and in Kubernetes Job labels. A sequential or guessable identifier would
 * let one team enumerate another team's runs.
 *
 * @see <a href="../../../../../../../../docs/adr/001-control-execution-separation.md">ADR 001</a>
 */
public interface ResourceId {

    /** The underlying value. Never {@code null}. */
    UUID value();

    /**
     * Parses a raw identifier, rejecting anything that is not a valid UUID.
     *
     * @param raw      the candidate value, typically from a URL path or request body
     * @param typeName the identifier type, used only to build a readable error message
     * @throws IllegalArgumentException if {@code raw} is null, blank or not a valid UUID
     */
    static UUID parse(String raw, String typeName) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(typeName + " must not be null or blank");
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            // The malformed value is deliberately not echoed: these arrive from untrusted input
            // and are frequently logged.
            throw new IllegalArgumentException(typeName + " must be a valid UUID");
        }
    }

    /**
     * Validates a UUID supplied directly rather than parsed from text.
     *
     * @throws IllegalArgumentException if {@code value} is null
     */
    static UUID require(UUID value, String typeName) {
        if (value == null) {
            throw new IllegalArgumentException(typeName + " must not be null");
        }
        return value;
    }
}
