package com.perfplatform.domain.identity;

import java.util.UUID;

/**
 * Identifies one immutable version of a test definition. A version cannot be modified after creation, so a run referencing it is exactly reproducible.
 *
 * @param value the underlying UUID, never {@code null}
 */
public record TestDefinitionVersionId(UUID value) implements ResourceId {

    public TestDefinitionVersionId {
        value = ResourceId.require(value, "TestDefinitionVersionId");
    }

    /** Parses a TestDefinitionVersionId from its external string form. */
    public static TestDefinitionVersionId of(String raw) {
        return new TestDefinitionVersionId(ResourceId.parse(raw, "TestDefinitionVersionId"));
    }

    /** Wraps an existing UUID as a TestDefinitionVersionId. */
    public static TestDefinitionVersionId of(UUID value) {
        return new TestDefinitionVersionId(value);
    }

    /** Allocates a new, random TestDefinitionVersionId. */
    public static TestDefinitionVersionId generate() {
        return new TestDefinitionVersionId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
