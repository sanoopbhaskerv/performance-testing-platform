package com.perfplatform.domain.identity;

import java.util.UUID;

/**
 * Identifies a compiled, immutable execution plan. The plan is derived from one specification digest and is never edited in place.
 *
 * @param value the underlying UUID, never {@code null}
 */
public record ExecutionPlanId(UUID value) implements ResourceId {

    public ExecutionPlanId {
        value = ResourceId.require(value, "ExecutionPlanId");
    }

    /** Parses a ExecutionPlanId from its external string form. */
    public static ExecutionPlanId of(String raw) {
        return new ExecutionPlanId(ResourceId.parse(raw, "ExecutionPlanId"));
    }

    /** Wraps an existing UUID as a ExecutionPlanId. */
    public static ExecutionPlanId of(UUID value) {
        return new ExecutionPlanId(value);
    }

    /** Allocates a new, random ExecutionPlanId. */
    public static ExecutionPlanId generate() {
        return new ExecutionPlanId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
