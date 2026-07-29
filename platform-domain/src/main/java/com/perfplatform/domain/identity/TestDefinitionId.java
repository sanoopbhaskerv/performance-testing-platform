package com.perfplatform.domain.identity;

import java.util.UUID;

/**
 * Identifies a logical test definition. The definition is mutable metadata; its versions are not.
 *
 * @param value the underlying UUID, never {@code null}
 */
public record TestDefinitionId(UUID value) implements ResourceId {

    public TestDefinitionId {
        value = ResourceId.require(value, "TestDefinitionId");
    }

    /** Parses a TestDefinitionId from its external string form. */
    public static TestDefinitionId of(String raw) {
        return new TestDefinitionId(ResourceId.parse(raw, "TestDefinitionId"));
    }

    /** Wraps an existing UUID as a TestDefinitionId. */
    public static TestDefinitionId of(UUID value) {
        return new TestDefinitionId(value);
    }

    /** Allocates a new, random TestDefinitionId. */
    public static TestDefinitionId generate() {
        return new TestDefinitionId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
