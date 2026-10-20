package com.perfplatform.persistence.progress;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.perfplatform.domain.progress.LatencyHistogram;
import com.perfplatform.domain.progress.LiveProgress;
import com.perfplatform.domain.progress.ProgressMetrics;
import com.perfplatform.domain.results.OperationMetrics;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The document half of a stored snapshot: everything that is not a scalar on the row.
 *
 * <p>Its own type rather than annotations on the domain record, because the domain must not acquire
 * a JSON binding (CLAUDE.md §9). This is a storage shape, and it is free to change with the schema
 * without dragging the model behind it.
 *
 * <p>Latency buckets are stored as counts plus their own edges. Storing the edges rather than
 * assuming the platform's is what lets a snapshot recorded by an older runner image still be read
 * correctly years later — the alternative silently reassigns every sample to the wrong bucket and
 * changes every percentile in a way nothing would ever flag.
 */
record RunProgressDetail(
        List<Long> latencyBoundsMillis,
        Metrics overall,
        Map<String, Metrics> byOperation,
        List<Shard> shards) {

    /** One operation's merged measurements, in the same integer forms the domain uses. */
    record Metrics(
            long sampleCount,
            long errorCount,
            long unparsedCount,
            long minMillis,
            long maxMillis,
            long meanMillis,
            long throughputPerSecondMilli,
            List<Long> latencyCounts) {
    }

    /**
     * One shard's own last word, which the merge cannot show.
     *
     * <p>The capture time is epoch milliseconds rather than an {@code Instant}. A long needs no date
     * module on the mapper and no format decision, and a stored row must still decode after a
     * dependency upgrade changes what the default serialization of a temporal type looks like.
     */
    record Shard(
            int shardIndex,
            long capturedAtEpochMillis,
            long sampleCount,
            long errorCount,
            long activeUsers) {
    }

    static RunProgressDetail from(LiveProgress progress) {
        Map<String, Metrics> operations = new LinkedHashMap<>();
        progress.byOperation().forEach((name, metrics) -> operations.put(name, metrics(metrics)));

        List<Shard> shards = progress.shards().stream()
                .map(shard -> new Shard(shard.shardIndex(), shard.capturedAt().toEpochMilli(),
                        shard.sampleCount(), shard.errorCount(), shard.activeUsers()))
                .toList();

        return new RunProgressDetail(progress.overall().latency().boundsMillis(),
                metrics(progress.overall()), operations, shards);
    }

    /**
     * Rebuilds the domain view from a stored row.
     *
     * @param runId   the run, from the row rather than the document — a document that named its own
     *                run could disagree with the row it is stored in
     * @param engine  likewise from the row
     */
    LiveProgress toLiveProgress(com.perfplatform.domain.identity.TestRunId runId, String engine,
            Instant capturedAt, Instant recordedAt, int shardsExpected, long elapsedSeconds,
            long activeUsers) {

        Map<String, ProgressMetrics> operations = new LinkedHashMap<>();
        byOperation.forEach((name, metrics) -> operations.put(name, metrics(name, metrics)));

        List<LiveProgress.ShardReport> reports = new ArrayList<>();
        shards.forEach(shard -> reports.add(new LiveProgress.ShardReport(shard.shardIndex(),
                Instant.ofEpochMilli(shard.capturedAtEpochMillis()), shard.sampleCount(),
                shard.errorCount(), shard.activeUsers())));

        return new LiveProgress(runId, engine, capturedAt, recordedAt, shardsExpected,
                elapsedSeconds, activeUsers, metrics(OperationMetrics.ALL, overall), operations,
                reports);
    }

    private static Metrics metrics(ProgressMetrics metrics) {
        return new Metrics(metrics.sampleCount(), metrics.errorCount(), metrics.unparsedCount(),
                metrics.minMillis(), metrics.maxMillis(), metrics.meanMillis(),
                metrics.throughputPerSecondMilli(), metrics.latency().counts());
    }

    private ProgressMetrics metrics(String name, Metrics stored) {
        return new ProgressMetrics(name, stored.sampleCount(), stored.errorCount(),
                stored.unparsedCount(), stored.minMillis(), stored.maxMillis(), stored.meanMillis(),
                stored.throughputPerSecondMilli(),
                new LatencyHistogram(latencyBoundsMillis, stored.latencyCounts()));
    }

    String encode(ObjectMapper mapper) {
        try {
            return mapper.writeValueAsString(this);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("A live progress snapshot could not be encoded", e);
        }
    }

    static RunProgressDetail decode(ObjectMapper mapper, String json) {
        try {
            return mapper.readValue(json, RunProgressDetail.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("A stored live progress snapshot could not be read", e);
        }
    }
}
