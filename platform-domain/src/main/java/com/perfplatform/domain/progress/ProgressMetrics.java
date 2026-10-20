package com.perfplatform.domain.progress;

import com.perfplatform.domain.results.OperationMetrics;

import java.util.Objects;

/**
 * What one shard has measured so far, for one operation or for everything.
 *
 * <p>The live counterpart of {@link OperationMetrics}, and deliberately not the same type. The
 * difference is the one that matters: this carries a {@link LatencyHistogram} instead of
 * percentiles, because a snapshot has to be <em>added</em> to other shards' snapshots and
 * percentiles cannot be added. {@link #toOperationMetrics()} converts once, after merging, at the
 * point where a number is finally being read rather than combined.
 *
 * <p>Counts are cumulative for the run so far. Throughput is not: it is measured over the window
 * since the previous snapshot, because "how fast is it going" is a question about now, and a
 * lifetime average hides a generator that stopped two minutes ago behind the traffic it sent before.
 *
 * @param name             the operation as {@code workload/scenario}, or {@link OperationMetrics#ALL}
 * @param sampleCount      requests measured since the run started
 * @param errorCount       of those, how many failed
 * @param unparsedCount    rows the runner could not read. Kept in the error-rate denominator for the
 *                         same reason the offline reducer keeps it: a denominator that excluded them
 *                         would improve the reported error rate the worse a run went
 * @param minMillis        fastest sample so far
 * @param maxMillis        slowest sample so far
 * @param meanMillis       arithmetic mean over every sample so far
 * @param throughputPerSecondMilli requests per second over the most recent window, scaled by 1,000
 * @param latency          the distribution, in mergeable buckets
 */
public record ProgressMetrics(
        String name,
        long sampleCount,
        long errorCount,
        long unparsedCount,
        long minMillis,
        long maxMillis,
        long meanMillis,
        long throughputPerSecondMilli,
        LatencyHistogram latency) {

    public ProgressMetrics {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(latency, "latency");
        if (sampleCount < 0 || errorCount < 0 || unparsedCount < 0) {
            throw new IllegalArgumentException("Counts must not be negative");
        }
        if (errorCount > sampleCount) {
            throw new IllegalArgumentException(
                    "errorCount (" + errorCount + ") cannot exceed sampleCount (" + sampleCount + ")");
        }
    }

    public static ProgressMetrics empty(String name) {
        return new ProgressMetrics(name, 0, 0, 0, 0, 0, 0, 0, LatencyHistogram.empty());
    }

    /**
     * Adds another shard's measurements for the same operation.
     *
     * <p>Counts and throughput sum, the extremes take the extreme, and the mean is weighted by
     * sample count — a straight average of two shards' means would let a shard that sent ten requests
     * pull the figure as hard as one that sent ten thousand.
     */
    public ProgressMetrics merge(ProgressMetrics other) {
        if (other == null) {
            return this;
        }
        long samples = sampleCount + other.sampleCount();
        long weightedMean = samples == 0
                ? 0
                : (meanMillis * sampleCount + other.meanMillis() * other.sampleCount()) / samples;

        return new ProgressMetrics(
                name,
                samples,
                errorCount + other.errorCount(),
                unparsedCount + other.unparsedCount(),
                sampleCount == 0 ? other.minMillis()
                        : other.sampleCount() == 0 ? minMillis
                        : Math.min(minMillis, other.minMillis()),
                Math.max(maxMillis, other.maxMillis()),
                weightedMean,
                throughputPerSecondMilli + other.throughputPerSecondMilli(),
                latency.merge(other.latency()));
    }

    /**
     * Error rate as a percentage scaled by 1,000, so {@code 1500} means 1.5%.
     *
     * <p>Same scaled-integer form as {@link OperationMetrics#errorRateMilliPercent()}, so a live
     * figure and a final one are directly comparable rather than nearly comparable.
     */
    public long errorRateMilliPercent() {
        long attempted = sampleCount + unparsedCount;
        return attempted == 0 ? 0 : (errorCount * 100_000L) / attempted;
    }

    public boolean hasSamples() {
        return sampleCount > 0;
    }

    /**
     * The merged view as the normalized shape objectives are evaluated against.
     *
     * <p>Percentiles are read off the histogram here — once, after every shard has been added. The
     * values are upper bounds (see {@link LatencyHistogram}), which is why an outcome derived from
     * them is presented as provisional and never stored as the run's verdict.
     */
    public OperationMetrics toOperationMetrics() {
        return new OperationMetrics(
                name,
                sampleCount,
                errorCount,
                unparsedCount,
                minMillis,
                maxMillis,
                meanMillis,
                latency.percentileMillis(50, maxMillis),
                latency.percentileMillis(90, maxMillis),
                latency.percentileMillis(95, maxMillis),
                latency.percentileMillis(99, maxMillis),
                throughputPerSecondMilli,
                latency.percentileMillis(75, maxMillis));
    }
}
