package com.perfplatform.domain.results;

import java.util.Objects;

/**
 * Measurements for one operation, or for a whole workload.
 *
 * <p>Engine-neutral by construction. A JMeter sampler, a k6 check and a Playwright step all reduce
 * to the same shape, which is what lets objectives be written once and evaluated against any engine.
 * Nothing here mentions samplers, JTL columns or thread groups.
 *
 * <p>Latencies are milliseconds. Whole milliseconds, not fractions: sub-millisecond precision is
 * not meaningful over a network, and an integral type keeps the values exactly reproducible from
 * stored artifacts — a percentile recomputed from the same samples must give the same answer, and
 * floating-point accumulation does not promise that.
 *
 * @param name          the operation, or {@link #ALL} for the aggregate across a workload
 * @param sampleCount   requests that produced a usable measurement
 * @param errorCount    requests that failed
 * @param unparsedCount rows that could not be read at all. Counted separately and deliberately: V1
 *                      counted a malformed row as a request that was neither a sample nor an error,
 *                      which quietly deflated the error rate (defect D5)
 * @param minMillis     fastest usable sample
 * @param maxMillis     slowest usable sample
 * @param meanMillis    arithmetic mean of usable samples
 * @param p50Millis     median
 * @param p90Millis     90th percentile
 * @param p95Millis     95th percentile
 * @param p99Millis     99th percentile
 * @param throughputPerSecondMilli throughput in requests per second, scaled by 1,000 so it is an
 *                      exact integer. Stored scaled rather than as a double for the same
 *                      reproducibility reason as the latencies
 */
public record OperationMetrics(
        String name,
        long sampleCount,
        long errorCount,
        long unparsedCount,
        long minMillis,
        long maxMillis,
        long meanMillis,
        long p50Millis,
        long p90Millis,
        long p95Millis,
        long p99Millis,
        long throughputPerSecondMilli,
        Long p75Millis) {

    /** Older summaries did not store p75. Null means unavailable, never an estimated p90. */
    public OperationMetrics(String name, long sampleCount, long errorCount, long unparsedCount,
            long minMillis, long maxMillis, long meanMillis, long p50Millis, long p90Millis,
            long p95Millis, long p99Millis, long throughputPerSecondMilli) {
        this(name, sampleCount, errorCount, unparsedCount, minMillis, maxMillis, meanMillis,
                p50Millis, p90Millis, p95Millis, p99Millis, throughputPerSecondMilli, null);
    }

    /** The name used for the aggregate across every operation in a workload. */
    public static final String ALL = "*";

    public OperationMetrics {
        Objects.requireNonNull(name, "name");
        if (sampleCount < 0 || errorCount < 0 || unparsedCount < 0) {
            throw new IllegalArgumentException("Counts must not be negative");
        }
        if (errorCount > sampleCount) {
            throw new IllegalArgumentException(
                    "errorCount (" + errorCount + ") cannot exceed sampleCount (" + sampleCount + ")");
        }
    }

    /** An operation that produced nothing. */
    public static OperationMetrics empty(String name) {
        return new OperationMetrics(name, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    /**
     * Error rate as a percentage scaled by 1,000, so {@code 1500} means 1.5%.
     *
     * <p>Scaled integer rather than a double because this is compared against a threshold to decide
     * pass or fail, and a comparison that can differ in the last bit between two runs of the same
     * analysis is not a deterministic verdict.
     *
     * <p>Unparsed rows are included in the denominator. They were requests the runner attempted; a
     * denominator that excluded them would report a better error rate the more badly the run went.
     */
    public long errorRateMilliPercent() {
        long attempted = sampleCount + unparsedCount;
        if (attempted == 0) {
            return 0;
        }
        return (errorCount * 100_000L) / attempted;
    }

    /** Requests per second, as a plain double. For display only, never for a pass/fail decision. */
    public double throughputPerSecond() {
        return throughputPerSecondMilli / 1000.0;
    }

    /** Whether this operation produced any usable measurement at all. */
    public boolean hasSamples() {
        return sampleCount > 0;
    }
}
