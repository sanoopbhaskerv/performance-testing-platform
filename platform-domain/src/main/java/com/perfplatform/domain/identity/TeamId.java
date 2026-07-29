package com.perfplatform.domain.identity;

import java.util.UUID;

/**
 * Identifies a team. Every tenant-owned resource carries one, and authorization is evaluated against it on every request.
 *
 * @param value the underlying UUID, never {@code null}
 */
public record TeamId(UUID value) implements ResourceId {

    public TeamId {
        value = ResourceId.require(value, "TeamId");
    }

    /** Parses a TeamId from its external string form. */
    public static TeamId of(String raw) {
        return new TeamId(ResourceId.parse(raw, "TeamId"));
    }

    /** Wraps an existing UUID as a TeamId. */
    public static TeamId of(UUID value) {
        return new TeamId(value);
    }

    /** Allocates a new, random TeamId. */
    public static TeamId generate() {
        return new TeamId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
