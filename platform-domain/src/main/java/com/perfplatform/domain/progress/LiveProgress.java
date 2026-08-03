package com.perfplatform.domain.progress;

import com.perfplatform.domain.identity.TestRunId;
import com.perfplatform.domain.results.NormalizedRunResult;
import com.perfplatform.domain.results.OperationMetrics;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * What a run is doing right now, with every shard's snapshot added together.
 *
 * <p>Until this existed a run showed a state and nothing else: {@code RUNNING} was true for twenty
 * minutes and said nothing about whether the error rate was climbing. For an experienced engineer
 * that is tolerable. For someone on their first test it is a spinner followed by a verdict they have
 * no context for — and no way to tell a run worth stopping from a healthy one until it is over.
 *
 * <h2>This is evidence, not a verdict</h2>
 * Everything here is provisional and stays provisional. The numbers come from reductions a runner
 * performed on partial output, the percentiles are bucket upper bounds (see {@link
 * LatencyHistogram}), and shards report at slightly different moments. A run's actual result is what
 * the analyzer computes from the complete raw results after every shard has finished, and nothing in
 * this type ever becomes that. It is kept, though, rather than discarded when the run ends: a run
 * that dies leaves its last snapshot as the only account of what was happening at the time.
 *
 * @param runId          the run
 * @param engine         the engine whose shards reported
 * @param capturedAt     the newest shard capture time in this merge — how current the numbers are
 * @param recordedAt     when the control plane merged them
 * @param shardsExpected how many shards the plan called for
 * @param elapsedSeconds the longest elapsed time any shard reported
 * @param activeUsers    virtual users or browser sessions active across every reporting shard
 * @param overall        every operation, every shard
 * @param byOperation    per operation, keyed by {@code workload/scenario}
 * @param shards         one entry per shard that has ever reported, in shard-index order
 */
public record LiveProgress(
        TestRunId runId,
        String engine,
        Instant capturedAt,
        Instant recordedAt,
        int shardsExpected,
        long elapsedSeconds,
        long activeUsers,
        ProgressMetrics overall,
        Map<String, ProgressMetrics> byOperation,
        List<ShardReport> shards) {

    /**
     * One shard's contribution, kept separately from the merge.
     *
     * <p>Because the merge cannot show a shard that stopped reporting. Two shards whose totals are
     * added look exactly like two shards where one died three minutes ago and the other kept going —
     * the sum still grows. The per-shard capture time is what turns that into a visible lost
     * heartbeat rather than a quiet halving of the load.
     *
     * @param shardIndex  zero-based position
     * @param capturedAt  when this shard last reduced its output
     * @param sampleCount its cumulative samples
     * @param errorCount  its cumulative errors
     * @param activeUsers its active users at that moment
     */
    public record ShardReport(
            int shardIndex,
            Instant capturedAt,
            long sampleCount,
            long errorCount,
            long activeUsers) {

        public ShardReport {
            Objects.requireNonNull(capturedAt, "capturedAt");
            if (shardIndex < 0) {
                throw new IllegalArgumentException("shardIndex must not be negative");
            }
        }

        /** How long since this shard said anything, from the reader's clock. */
        public long ageSeconds(Instant now) {
            return Math.max(0, now.getEpochSecond() - capturedAt.getEpochSecond());
        }
    }

    public LiveProgress {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(capturedAt, "capturedAt");
        Objects.requireNonNull(recordedAt, "recordedAt");
        Objects.requireNonNull(overall, "overall");
        if (engine == null || engine.isBlank()) {
            throw new IllegalArgumentException("engine must not be null or blank");
        }
        if (shardsExpected < 1) {
            throw new IllegalArgumentException("shardsExpected must be at least 1");
        }
        byOperation = byOperation == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(byOperation));
        shards = shards == null ? List.of() : List.copyOf(shards);
    }

    /**
     * Adds every shard's snapshot into one view.
     *
     * @param snapshots      the snapshots to merge; at most one per shard, newest per shard
     * @param shardsExpected how many the plan called for, which may exceed the number reporting
     * @param recordedAt     the control plane's clock
     * @return empty when no shard has reported anything yet — a run with no live view is a real
     *         state, not a view full of zeros, and zeros would read as "measured nothing" rather
     *         than "not measured yet"
     */
    public static Optional<LiveProgress> merge(List<ShardProgress> snapshots, int shardsExpected,
            Instant recordedAt) {

        if (snapshots == null || snapshots.isEmpty()) {
            return Optional.empty();
        }

        List<ShardProgress> ordered = new ArrayList<>(snapshots);
        ordered.sort((left, right) -> Integer.compare(left.shardIndex(), right.shardIndex()));

        ProgressMetrics overall = ProgressMetrics.empty(OperationMetrics.ALL);
        Map<String, ProgressMetrics> byOperation = new LinkedHashMap<>();
        List<ShardReport> reports = new ArrayList<>();

        Instant capturedAt = Instant.EPOCH;
        long elapsedSeconds = 0;
        long activeUsers = 0;

        for (ShardProgress snapshot : ordered) {
            overall = overall.merge(snapshot.overall());
            snapshot.byOperation().forEach((operation, metrics) -> byOperation.merge(
                    operation, metrics, ProgressMetrics::merge));

            // The newest capture, not the oldest: this is "how current is the freshest number",
            // and the per-shard ages below are what expose a shard whose numbers are not current.
            if (snapshot.capturedAt().isAfter(capturedAt)) {
                capturedAt = snapshot.capturedAt();
            }
            elapsedSeconds = Math.max(elapsedSeconds, snapshot.elapsedSeconds());
            activeUsers += snapshot.activeUsers();

            reports.add(new ShardReport(snapshot.shardIndex(), snapshot.capturedAt(),
                    snapshot.overall().sampleCount(), snapshot.overall().errorCount(),
                    snapshot.activeUsers()));
        }

        return Optional.of(new LiveProgress(
                ordered.get(0).runId(),
                ordered.get(0).engine(),
                capturedAt,
                recordedAt,
                Math.max(shardsExpected, ordered.size()),
                elapsedSeconds,
                activeUsers,
                overall,
                byOperation,
                reports));
    }

    /** How many shards have reported at least once. */
    public int shardsReporting() {
        return shards.size();
    }

    /** Whether every shard the plan called for is accounted for in this view. */
    public boolean isComplete() {
        return shardsReporting() >= shardsExpected;
    }

    /** How stale the freshest number is, from the reader's clock. */
    public long ageSeconds(Instant now) {
        return Math.max(0, now.getEpochSecond() - capturedAt.getEpochSecond());
    }

    /** The shard that has been quiet longest, if any shard has reported. */
    public Optional<ShardReport> stalestShard() {
        return shards.stream().min((left, right) -> left.capturedAt().compareTo(right.capturedAt()));
    }

    /**
     * The merged view in the shape objectives are evaluated against.
     *
     * <p>Reusing {@link NormalizedRunResult} rather than inventing a live-only comparison is the
     * point: there is one definition of "this objective is breached" in the platform, and a live
     * warning that used a looser one would eventually disagree with the run's own verdict and
     * destroy the credibility of both.
     *
     * <p>{@code shardsReported} is the number that have actually reported, so a run with a silent
     * shard produces indeterminate live objectives — exactly as the final analysis would.
     */
    public NormalizedRunResult asNormalizedResult() {
        Map<String, OperationMetrics> operations = new LinkedHashMap<>();
        byOperation.forEach((name, metrics) -> operations.put(name, metrics.toOperationMetrics()));

        return new NormalizedRunResult(
                runId,
                engine,
                Optional.empty(),
                Optional.empty(),
                shardsExpected,
                Math.min(shardsReporting(), shardsExpected),
                overall.toOperationMetrics(),
                operations);
    }
}
