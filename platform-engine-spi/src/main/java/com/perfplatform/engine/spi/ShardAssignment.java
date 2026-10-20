package com.perfplatform.engine.spi;

import com.perfplatform.domain.identity.TestRunId;

import java.util.Objects;

/**
 * What distinguishes one shard's rendered workload from another's.
 *
 * <p>Two shards of a run execute the same plan but must not execute it identically: each takes a
 * non-overlapping slice of the dataset, or sharding merely makes every generator replay the same
 * rows and the test measures a cache rather than the system.
 *
 * @param runId             the run, and the value injected as the correlation header so
 *                          application-side observability can attribute the load it sees
 * @param shardIndex        zero-based; selects this shard's dataset slice
 * @param shardCount        total shards, so the runner can partition without a second call
 * @param correlationHeader the header name to inject, e.g. {@code X-Performance-Test-Run-Id}
 */
public record ShardAssignment(
        TestRunId runId,
        int shardIndex,
        int shardCount,
        String correlationHeader) {

    /** The header the platform injects unless a specification asks for a different one. */
    public static final String DEFAULT_CORRELATION_HEADER = "X-Performance-Test-Run-Id";

    public ShardAssignment {
        Objects.requireNonNull(runId, "runId");
        if (shardCount < 1) {
            throw new IllegalArgumentException("shardCount must be at least 1");
        }
        if (shardIndex < 0 || shardIndex >= shardCount) {
            throw new IllegalArgumentException(
                    "shardIndex " + shardIndex + " is outside the range of " + shardCount + " shards");
        }
        correlationHeader = correlationHeader == null || correlationHeader.isBlank()
                ? DEFAULT_CORRELATION_HEADER : correlationHeader.trim();
    }

    /** The value carried in the correlation header: the run identifier. */
    public String correlationValue() {
        return runId.toString();
    }
}
