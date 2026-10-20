package com.perfplatform.persistence.artifact;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Storage row for one operation's measurements.
 *
 * <p>One row per operation, plus one for the whole-run aggregate under the reserved name {@code *}.
 * These are the numbers behind a verdict: the latency distribution, the error counts, the throughput.
 * Until Increment 1D they were computed during analysis and discarded, leaving a stored verdict
 * nobody could see the evidence for.
 *
 * <p>Every value is an integer, matching {@code OperationMetrics}. Latencies are whole milliseconds
 * and throughput is scaled by 1,000. Not floating point: these are the values a threshold is compared
 * against, and a number that can differ in the last bit between two reads is not a verdict.
 *
 * <p>Shard counts and the sample window are carried on every row rather than once per run. That is
 * deliberate denormalization: a p95 is only meaningful alongside whether every shard reported, and
 * requiring a second lookup to learn that makes it possible to read the number without the caveat.
 */
@Entity
@Table(name = "run_metrics")
public class RunMetricEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "run_id", nullable = false, updatable = false)
    private UUID runId;

    /** {@code workload/scenario}, or {@code *} for the aggregate across the whole run. */
    @Column(name = "operation", nullable = false, updatable = false, length = 255)
    private String operation;

    /**
     * Which engine produced the samples.
     *
     * <p>Stored here rather than read from the execution plan so a metrics row is self-describing.
     * A p95 whose engine can only be discovered by joining another table stops meaning anything if
     * that row is ever pruned.
     */
    @Column(name = "engine", nullable = false, updatable = false, length = 64)
    private String engine;

    @Column(name = "sample_count", nullable = false)
    private long sampleCount;

    @Column(name = "error_count", nullable = false)
    private long errorCount;

    /**
     * Rows the engine could not read.
     *
     * <p>Kept separate from errors and kept in the error-rate denominator. V1 counted a malformed row
     * as neither, which quietly improved the reported error rate the more badly a run went (D5).
     */
    @Column(name = "unparsed_count", nullable = false)
    private long unparsedCount;

    @Column(name = "min_millis", nullable = false)
    private long minMillis;

    @Column(name = "max_millis", nullable = false)
    private long maxMillis;

    @Column(name = "mean_millis", nullable = false)
    private long meanMillis;

    @Column(name = "p50_millis", nullable = false)
    private long p50Millis;

    @Column(name = "p75_millis")
    private Long p75Millis;

    @Column(name = "p90_millis", nullable = false)
    private long p90Millis;

    @Column(name = "p95_millis", nullable = false)
    private long p95Millis;

    @Column(name = "p99_millis", nullable = false)
    private long p99Millis;

    /** Requests per second scaled by 1,000, so it stays exact. */
    @Column(name = "throughput_per_second_milli", nullable = false)
    private long throughputPerSecondMilli;

    @Column(name = "shards_expected", nullable = false)
    private int shardsExpected;

    @Column(name = "shards_reported", nullable = false)
    private int shardsReported;

    /** First sample. Null when nothing was measured. */
    @Column(name = "measured_from")
    private Instant measuredFrom;

    /** Last sample, including its elapsed time. Null when nothing was measured. */
    @Column(name = "measured_to")
    private Instant measuredTo;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    protected RunMetricEntity() {
        // Required by JPA.
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    RunMetricEntity(UUID id, UUID runId, String operation, String engine,
            long sampleCount, long errorCount, long unparsedCount,
            long minMillis, long maxMillis, long meanMillis,
            long p50Millis, long p90Millis, long p95Millis, long p99Millis,
            long throughputPerSecondMilli, int shardsExpected, int shardsReported,
            Instant measuredFrom, Instant measuredTo, Instant recordedAt) {

        this.id = Objects.requireNonNull(id, "id");
        this.runId = Objects.requireNonNull(runId, "runId");
        this.operation = Objects.requireNonNull(operation, "operation");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.sampleCount = sampleCount;
        this.errorCount = errorCount;
        this.unparsedCount = unparsedCount;
        this.minMillis = minMillis;
        this.maxMillis = maxMillis;
        this.meanMillis = meanMillis;
        this.p50Millis = p50Millis;
        this.p90Millis = p90Millis;
        this.p95Millis = p95Millis;
        this.p99Millis = p99Millis;
        this.throughputPerSecondMilli = throughputPerSecondMilli;
        this.shardsExpected = shardsExpected;
        this.shardsReported = shardsReported;
        this.measuredFrom = measuredFrom;
        this.measuredTo = measuredTo;
        this.recordedAt = Objects.requireNonNull(recordedAt, "recordedAt");
    }

    public UUID getId() {
        return id;
    }

    public UUID getRunId() {
        return runId;
    }

    public String getOperation() {
        return operation;
    }

    public String getEngine() {
        return engine;
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

    public long getMinMillis() {
        return minMillis;
    }

    public long getMaxMillis() {
        return maxMillis;
    }

    public long getMeanMillis() {
        return meanMillis;
    }

    public long getP50Millis() {
        return p50Millis;
    }

    public Long getP75Millis() { return p75Millis; }
    void setP75Millis(Long value) { p75Millis = value; }

    public long getP90Millis() {
        return p90Millis;
    }

    public long getP95Millis() {
        return p95Millis;
    }

    public long getP99Millis() {
        return p99Millis;
    }

    public long getThroughputPerSecondMilli() {
        return throughputPerSecondMilli;
    }

    public int getShardsExpected() {
        return shardsExpected;
    }

    public int getShardsReported() {
        return shardsReported;
    }

    public Instant getMeasuredFrom() {
        return measuredFrom;
    }

    public Instant getMeasuredTo() {
        return measuredTo;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }
}
