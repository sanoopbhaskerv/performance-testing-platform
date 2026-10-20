package com.perfplatform.domain.identity;

import java.util.UUID;

/**
 * Identifies an application owned by a team. Applications carry the environment and policy metadata that governs which targets may be tested.
 *
 * @param value the underlying UUID, never {@code null}
 */
public record ApplicationId(UUID value) implements ResourceId {

    public ApplicationId {
        value = ResourceId.require(value, "ApplicationId");
    }

    /** Parses a ApplicationId from its external string form. */
    public static ApplicationId of(String raw) {
        return new ApplicationId(ResourceId.parse(raw, "ApplicationId"));
    }

    /** Wraps an existing UUID as a ApplicationId. */
    public static ApplicationId of(UUID value) {
        return new ApplicationId(value);
    }

    /** Allocates a new, random ApplicationId. */
    public static ApplicationId generate() {
        return new ApplicationId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
