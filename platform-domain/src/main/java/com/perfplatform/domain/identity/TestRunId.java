package com.perfplatform.domain.identity;

import java.util.UUID;

/**
 * Identifies a single run. Also used as the correlation value injected into workload traffic and as an artifact key prefix, so it must not be guessable.
 *
 * @param value the underlying UUID, never {@code null}
 */
public record TestRunId(UUID value) implements ResourceId {

    public TestRunId {
        value = ResourceId.require(value, "TestRunId");
    }

    /** Parses a TestRunId from its external string form. */
    public static TestRunId of(String raw) {
        return new TestRunId(ResourceId.parse(raw, "TestRunId"));
    }

    /** Wraps an existing UUID as a TestRunId. */
    public static TestRunId of(UUID value) {
        return new TestRunId(value);
    }

    /** Allocates a new, random TestRunId. */
    public static TestRunId generate() {
        return new TestRunId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
