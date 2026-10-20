package com.perfplatform.domain.results;

import com.perfplatform.domain.identity.TestRunId;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * What a run measured, after engine-specific output has been reduced to a common shape.
 *
 * <p>Objectives are evaluated against this and nothing else. That is the whole point of the type:
 * a threshold written once must mean the same thing whether the numbers came from JMeter, from a
 * browser journey, or from an engine that does not exist yet.
 *
 * <h2>Completeness is part of the result</h2>
 * {@link #shardsReported} and {@link #shardsExpected} are carried because a verdict computed from
 * a partial result set is not a verdict. A run whose second shard never reported measured roughly
 * half the load its plan describes, and reporting the surviving shard's latencies as the test's
 * outcome would state a pass the test never demonstrated. Evaluation refuses to pass an incomplete
 * result rather than quietly averaging what it has.
 *
 * @param runId          the run these measurements belong to
 * @param engine         which engine produced them
 * @param startedAt      first sample
 * @param completedAt    last sample
 * @param shardsExpected how many shards the plan called for
 * @param shardsReported how many produced usable output
 * @param overall        measurements across every operation
 * @param byOperation    measurements per operation, keyed by name, in a stable order
 */
public record NormalizedRunResult(
        TestRunId runId,
        String engine,
        Optional<Instant> startedAt,
        Optional<Instant> completedAt,
        int shardsExpected,
        int shardsReported,
        OperationMetrics overall,
        Map<String, OperationMetrics> byOperation) {

    public NormalizedRunResult {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(overall, "overall");
        if (engine == null || engine.isBlank()) {
            throw new IllegalArgumentException("engine must not be null or blank");
        }
        if (shardsExpected < 1) {
            throw new IllegalArgumentException("shardsExpected must be at least 1");
        }
        if (shardsReported < 0 || shardsReported > shardsExpected) {
            throw new IllegalArgumentException(
                    "shardsReported (" + shardsReported + ") must be between 0 and shardsExpected ("
                            + shardsExpected + ")");
        }
        startedAt = startedAt == null ? Optional.empty() : startedAt;
        completedAt = completedAt == null ? Optional.empty() : completedAt;
        // Defensive copy into a LinkedHashMap, then wrapped — not Map.copyOf, which returns an
        // unordered map whose iteration order is randomized per JVM run. That would make a report's
        // operation ordering differ between two summaries of identical samples, which is exactly the
        // non-determinism the reducer's sorted grouping exists to avoid.
        byOperation = byOperation == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(byOperation));
    }

    /** A run that produced nothing usable. */
    public static NormalizedRunResult empty(TestRunId runId, String engine, int shardsExpected) {
        return new NormalizedRunResult(runId, engine, Optional.empty(), Optional.empty(),
                shardsExpected, 0, OperationMetrics.empty(OperationMetrics.ALL), Map.of());
    }

    /**
     * Whether every shard the plan called for reported.
     *
     * <p>Objective evaluation must not report a pass when this is false. A partial result set
     * measures less load than was asked for, and the numbers it contains describe a smaller test.
     */
    public boolean isComplete() {
        return shardsReported == shardsExpected;
    }

    /** Measurements for one operation, or empty if it produced none. */
    public Optional<OperationMetrics> operation(String name) {
        return Optional.ofNullable(byOperation.get(name));
    }

    /** Operation names, in the order they were reduced. */
    public List<String> operationNames() {
        return List.copyOf(byOperation.keySet());
    }
}
