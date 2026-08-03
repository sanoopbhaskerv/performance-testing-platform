package com.perfplatform.domain.progress;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Latency counts in fixed buckets, so several shards' distributions can be added together.
 *
 * <p>This type exists because of one arithmetic fact: <b>percentiles do not merge</b>. The p95 of
 * two shards is not the mean of their p95s, and it is not the larger of them either. A live view
 * that averaged them would print a number no sample supports — and it would do so while somebody
 * watched a run and decided whether to stop it.
 *
 * <p>So a runner never reports a percentile. It reports how many samples fell in each bucket, the
 * control plane adds the buckets across shards, and the percentile is read off the merged
 * distribution. Addition is exact; only the bucket resolution is approximate, and that error is
 * bounded and stated rather than hidden.
 *
 * <h2>A percentile from this is an upper bound</h2>
 * {@link #percentileMillis(int)} returns the <em>upper edge</em> of the bucket the requested sample
 * falls in — "at most this". It never interpolates. Interpolation would produce a plausible number
 * that is not derived from anything, which is the failure mode the whole design is avoiding, and the
 * conservative direction is the safe one for a live view: a latency ceiling that looks breached here
 * is confirmed or cleared by the final analysis, which reduces the raw samples exactly. That is why
 * every live objective is labelled provisional and never becomes a run's verdict.
 *
 * <h2>Why the bounds travel with the counts</h2>
 * A snapshot carries its own bucket edges rather than assuming this class's. Runner images are
 * versioned separately from the control plane, and a control plane that assumed the edges would
 * silently misread a snapshot from an older image — reporting confident, wrong latencies. Snapshots
 * whose edges disagree are refused for merging instead ({@link #canMerge}).
 */
public record LatencyHistogram(List<Long> boundsMillis, List<Long> counts) {

    /**
     * The bucket edges this platform's runners use.
     *
     * <p>Chosen so relative resolution stays roughly constant across the range that matters: tight
     * where a fast API lives (single-digit to low-hundreds of milliseconds, where a threshold is
     * usually written), coarser out where the only question is "seconds, or many seconds". The last
     * bucket is unbounded and its samples are reported at the observed maximum rather than at
     * infinity.
     */
    public static final List<Long> DEFAULT_BOUNDS_MILLIS = List.of(
            1L, 2L, 3L, 5L, 8L, 10L, 15L, 20L, 30L, 50L, 75L, 100L, 150L, 200L, 300L, 500L, 750L,
            1_000L, 1_500L, 2_000L, 3_000L, 5_000L, 7_500L, 10_000L, 15_000L, 20_000L, 30_000L,
            60_000L);

    public LatencyHistogram {
        Objects.requireNonNull(boundsMillis, "boundsMillis");
        Objects.requireNonNull(counts, "counts");
        // One more count than bounds: the overflow bucket for everything past the last edge. A
        // snapshot that disagrees about this is malformed rather than merely unexpected — reading it
        // would shift every sample into the wrong bucket and change every percentile.
        if (counts.size() != boundsMillis.size() + 1) {
            throw new IllegalArgumentException(
                    "A histogram needs one more count than it has bounds (bounds="
                            + boundsMillis.size() + ", counts=" + counts.size() + ")");
        }
        if (counts.stream().anyMatch(count -> count == null || count < 0)) {
            throw new IllegalArgumentException("Bucket counts must not be null or negative");
        }
        boundsMillis = List.copyOf(boundsMillis);
        counts = List.copyOf(counts);
    }

    /** An empty histogram over the platform's own bucket edges. */
    public static LatencyHistogram empty() {
        return empty(DEFAULT_BOUNDS_MILLIS);
    }

    public static LatencyHistogram empty(List<Long> bounds) {
        Long[] counts = new Long[bounds.size() + 1];
        Arrays.fill(counts, 0L);
        return new LatencyHistogram(bounds, Arrays.asList(counts));
    }

    /** A histogram over the default bounds, built by placing each latency in its bucket. */
    public static LatencyHistogram of(long... latenciesMillis) {
        long[] counts = new long[DEFAULT_BOUNDS_MILLIS.size() + 1];
        for (long latency : latenciesMillis) {
            counts[bucketOf(DEFAULT_BOUNDS_MILLIS, latency)]++;
        }
        return new LatencyHistogram(DEFAULT_BOUNDS_MILLIS,
                Arrays.stream(counts).boxed().toList());
    }

    private static int bucketOf(List<Long> bounds, long latencyMillis) {
        for (int index = 0; index < bounds.size(); index++) {
            if (latencyMillis <= bounds.get(index)) {
                return index;
            }
        }
        return bounds.size();
    }

    /** Total samples across every bucket. */
    public long totalCount() {
        return counts.stream().mapToLong(Long::longValue).sum();
    }

    /** Whether two histograms describe the same buckets and can therefore be added. */
    public boolean canMerge(LatencyHistogram other) {
        return other != null && boundsMillis.equals(other.boundsMillis());
    }

    /**
     * Adds another shard's distribution to this one.
     *
     * @throws IllegalArgumentException if the bucket edges differ. Refused rather than reconciled:
     *                                  re-bucketing one distribution into another's edges would
     *                                  require assuming where inside a bucket its samples fell,
     *                                  which is the assumption this type exists to avoid
     */
    public LatencyHistogram merge(LatencyHistogram other) {
        if (!canMerge(other)) {
            throw new IllegalArgumentException(
                    "Histograms with different bucket bounds cannot be merged. This snapshot was "
                            + "produced by a runner image that does not match the control plane's "
                            + "bucket contract.");
        }
        Long[] merged = new Long[counts.size()];
        for (int index = 0; index < counts.size(); index++) {
            merged[index] = counts.get(index) + other.counts().get(index);
        }
        return new LatencyHistogram(boundsMillis, Arrays.asList(merged));
    }

    /**
     * The requested percentile, as the upper edge of the bucket it falls in.
     *
     * @param percentile 1–100
     * @param observedMaxMillis the largest latency actually seen, used for samples in the unbounded
     *                          overflow bucket. Reporting {@code Long.MAX_VALUE} there would be
     *                          technically true and useless; reporting the observed maximum is the
     *                          tightest bound the data supports
     * @return the bound in milliseconds, or 0 when nothing has been measured
     */
    public long percentileMillis(int percentile, long observedMaxMillis) {
        if (percentile < 1 || percentile > 100) {
            throw new IllegalArgumentException("percentile must be between 1 and 100");
        }
        long total = totalCount();
        if (total == 0) {
            return 0;
        }

        // Nearest-rank: the smallest bucket whose cumulative count reaches ceil(p/100 × n). The same
        // definition the offline reducer uses, so a live number and a final number differ only by
        // bucket resolution rather than by method.
        long rank = (long) Math.ceil((percentile / 100.0) * total);
        long cumulative = 0;
        for (int index = 0; index < counts.size(); index++) {
            cumulative += counts.get(index);
            if (cumulative >= rank) {
                return index < boundsMillis.size()
                        ? boundsMillis.get(index)
                        : Math.max(observedMaxMillis, boundsMillis.get(boundsMillis.size() - 1));
            }
        }
        return observedMaxMillis;
    }
}
