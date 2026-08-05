package com.perfplatform.domain.identity;

import java.util.UUID;

/**
 * Identifies one interactive browser recording session.
 *
 * @param value the underlying UUID, never {@code null}
 */
public record RecorderSessionId(UUID value) implements ResourceId {

    public RecorderSessionId {
        value = ResourceId.require(value, "RecorderSessionId");
    }

    /** Parses a RecorderSessionId from its external string form. */
    public static RecorderSessionId of(String raw) {
        return new RecorderSessionId(ResourceId.parse(raw, "RecorderSessionId"));
    }

    /** Wraps an existing UUID as a RecorderSessionId. */
    public static RecorderSessionId of(UUID value) {
        return new RecorderSessionId(value);
    }

    /** Allocates a new, random RecorderSessionId. */
    public static RecorderSessionId generate() {
        return new RecorderSessionId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
