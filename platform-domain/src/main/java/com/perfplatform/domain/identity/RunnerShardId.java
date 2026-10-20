package com.perfplatform.domain.identity;

import java.util.UUID;

/**
 * Identifies one runner shard within a run. A run is executed by one or more shards, each covering a non-overlapping partition of the load and the dataset.
 *
 * @param value the underlying UUID, never {@code null}
 */
public record RunnerShardId(UUID value) implements ResourceId {

    public RunnerShardId {
        value = ResourceId.require(value, "RunnerShardId");
    }

    /** Parses a RunnerShardId from its external string form. */
    public static RunnerShardId of(String raw) {
        return new RunnerShardId(ResourceId.parse(raw, "RunnerShardId"));
    }

    /** Wraps an existing UUID as a RunnerShardId. */
    public static RunnerShardId of(UUID value) {
        return new RunnerShardId(value);
    }

    /** Allocates a new, random RunnerShardId. */
    public static RunnerShardId generate() {
        return new RunnerShardId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
