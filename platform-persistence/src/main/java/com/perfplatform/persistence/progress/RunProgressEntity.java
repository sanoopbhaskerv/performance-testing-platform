package com.perfplatform.persistence.progress;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Storage row for one merged live snapshot.
 *
 * <p>The aggregate is in columns and the rest is a document. That split is not laziness: the two
 * reads this table serves are "the latest point" and "the series, for a sparkline", and both want
 * the same handful of scalars from every row. The per-operation breakdown, the latency buckets and
 * the per-shard capture times are read whole when a person opens the run, which is exactly what a
 * document column is for.
 *
 * <p>Nothing here is evidence. The percentile is a bucket upper bound and the counts come from
 * partial runner output; a run's verdict is computed from the complete results and lives in
 * {@code run_metrics}. Keeping that clear is why the column is named {@code p95_millis} and the
 * class carries this paragraph rather than the two being conflated at some later reading.
 */
@Entity
@Table(name = "run_progress")
public class RunProgressEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "run_id", nullable = false, updatable = false)
    private UUID runId;

    @Column(name = "engine", nullable = false, updatable = false, length = 64)
    private String engine;

    /** The freshest shard capture in this merge, by the runner's clock. */
    @Column(name = "captured_at", nullable = false, updatable = false)
    private Instant capturedAt;

    /** When the control plane merged it, by its own clock. */
    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    @Column(name = "shards_expected", nullable = false, updatable = false)
    private int shardsExpected;

    @Column(name = "shards_reporting", nullable = false, updatable = false)
    private int shardsReporting;

    @Column(name = "elapsed_seconds", nullable = false, updatable = false)
    private long elapsedSeconds;

    @Column(name = "active_users", nullable = false, updatable = false)
    private long activeUsers;

    @Column(name = "sample_count", nullable = false, updatable = false)
    private long sampleCount;

    @Column(name = "error_count", nullable = false, updatable = false)
    private long errorCount;

    @Column(name = "unparsed_count", nullable = false, updatable = false)
    private long unparsedCount;

    @Column(name = "throughput_per_second_milli", nullable = false, updatable = false)
    private long throughputPerSecondMilli;

    /** A bucket upper bound, not a computed percentile. Lifted out of the document for the series. */
    @Column(name = "p95_millis", nullable = false, updatable = false)
    private long p95Millis;

    @Column(name = "detail_json", nullable = false, updatable = false)
    private String detailJson;

    protected RunProgressEntity() {
        // JPA
    }

    public RunProgressEntity(UUID id, UUID runId, String engine, Instant capturedAt,
            Instant recordedAt, int shardsExpected, int shardsReporting, long elapsedSeconds,
            long activeUsers, long sampleCount, long errorCount, long unparsedCount,
            long throughputPerSecondMilli, long p95Millis, String detailJson) {
        this.id = id;
        this.runId = runId;
        this.engine = engine;
        this.capturedAt = capturedAt;
        this.recordedAt = recordedAt;
        this.shardsExpected = shardsExpected;
        this.shardsReporting = shardsReporting;
        this.elapsedSeconds = elapsedSeconds;
        this.activeUsers = activeUsers;
        this.sampleCount = sampleCount;
        this.errorCount = errorCount;
        this.unparsedCount = unparsedCount;
        this.throughputPerSecondMilli = throughputPerSecondMilli;
        this.p95Millis = p95Millis;
        this.detailJson = detailJson;
    }

    public UUID getId() {
        return id;
    }

    public UUID getRunId() {
        return runId;
    }

    public String getEngine() {
        return engine;
    }

    public Instant getCapturedAt() {
        return capturedAt;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }

    public int getShardsExpected() {
        return shardsExpected;
    }

    public int getShardsReporting() {
        return shardsReporting;
    }

    public long getElapsedSeconds() {
        return elapsedSeconds;
    }

    public long getActiveUsers() {
        return activeUsers;
    }

    public long getSampleCount() {
        return sampleCount;
    }

    public long getErrorCount() {
        return errorCount;
    }

    public long getUnparsedCount() {
        return unparsedCount;
    }

    public long getThroughputPerSecondMilli() {
        return throughputPerSecondMilli;
    }

    public long getP95Millis() {
        return p95Millis;
    }

    public String getDetailJson() {
        return detailJson;
    }
}
