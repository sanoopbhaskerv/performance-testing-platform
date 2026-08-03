package com.perfplatform.domain.progress;

import com.perfplatform.domain.identity.RunnerShardId;
import com.perfplatform.domain.identity.TestRunId;
import com.perfplatform.domain.results.OperationMetrics;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One shard's account of itself, at one moment, while it is still running.
 *
 * <p>A runner writes this to object storage every few seconds and the control plane reads it. It is
 * <b>not</b> a callback, and the difference is a security property rather than a style preference: a
 * runner holds no control-plane credential and no database access by design, because a provider able
 * to write run state could mark its own failures as successes. It already holds object-storage
 * credentials, so publishing there costs nothing new.
 *
 * <h2>Identity is carried so it can be checked</h2>
 * {@code runId} and {@code planDigest} are repeated inside the document even though the object key
 * already implies them. A stale snapshot left behind by a previous run, or an object written under a
 * reused key, would otherwise contribute real-looking numbers to the wrong run's live view — the
 * same failure the runner manifest's digest check exists to prevent, and just as invisible.
 *
 * @param runId          the run these measurements belong to
 * @param shardId        the shard that produced them
 * @param shardIndex     zero-based position within the run
 * @param shardCount     how many shards the plan called for
 * @param engine         which engine produced them
 * @param planDigest     the plan the runner was given, as {@code sha256:…}
 * @param capturedAt     when the runner reduced its output, by the runner's clock
 * @param elapsedSeconds how long the runner has been executing
 * @param windowSeconds  the period the throughput figures cover
 * @param activeUsers    virtual users or browser sessions currently active, as the engine reports it
 * @param overall        every operation together
 * @param byOperation    per operation, keyed by {@code workload/scenario}
 */
public record ShardProgress(
        TestRunId runId,
        RunnerShardId shardId,
        int shardIndex,
        int shardCount,
        String engine,
        String planDigest,
        Instant capturedAt,
        long elapsedSeconds,
        long windowSeconds,
        long activeUsers,
        ProgressMetrics overall,
        Map<String, ProgressMetrics> byOperation) {

    /** The object name a runner publishes its rolling snapshot under, within its own shard prefix. */
    public static final String OBJECT_NAME = "progress.json";

    /** The document shape, carried in the file so a decoder never has to guess a version. */
    public static final String SCHEMA = "perfplatform.progress/v1";

    public ShardProgress {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(shardId, "shardId");
        Objects.requireNonNull(capturedAt, "capturedAt");
        Objects.requireNonNull(overall, "overall");
        if (engine == null || engine.isBlank()) {
            throw new IllegalArgumentException("engine must not be null or blank");
        }
        if (shardIndex < 0 || shardCount < 1 || shardIndex >= shardCount) {
            throw new IllegalArgumentException(
                    "shardIndex " + shardIndex + " is not within shardCount " + shardCount);
        }
        if (elapsedSeconds < 0 || windowSeconds < 0 || activeUsers < 0) {
            throw new IllegalArgumentException("Durations and user counts must not be negative");
        }
        planDigest = planDigest == null ? "" : planDigest;
        // Insertion order preserved rather than Map.copyOf, whose iteration order is randomized per
        // JVM. A live table whose rows reshuffled between two polls would be unreadable.
        byOperation = byOperation == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(byOperation));
    }

    /** How stale this snapshot is, from the reader's clock. */
    public long ageSeconds(Instant now) {
        return Math.max(0, now.getEpochSecond() - capturedAt.getEpochSecond());
    }

    /**
     * Whether this snapshot describes the run and plan it is being read for.
     *
     * <p>An empty digest is accepted: a runner image that predates the digest field has said nothing
     * rather than said something wrong. A <em>different</em> digest is refused, as with results.
     */
    public boolean describes(TestRunId expectedRun, String expectedPlanDigest) {
        if (!runId.equals(expectedRun)) {
            return false;
        }
        return planDigest.isBlank() || planDigest.equals(expectedPlanDigest);
    }

    /** The whole-run aggregate as the normalized shape, for display or evaluation. */
    public OperationMetrics overallAsOperationMetrics() {
        return overall.toOperationMetrics();
    }
}
