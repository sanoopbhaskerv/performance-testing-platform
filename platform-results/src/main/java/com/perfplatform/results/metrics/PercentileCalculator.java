package com.perfplatform.results.metrics;

import java.util.List;

/**
 * Latency percentile computation for normalized run results.
 *
 * <p>Uses the nearest-rank method: the percentile is the value at index
 * {@code ceil(p / 100 * n) - 1} in the ascending sample list. This is the algorithm V1's
 * {@code SlaEvaluator} has always used, extracted here unchanged so that objective evaluation
 * stays deterministic and reproducible from stored artifacts across the V1-to-V2 migration.
 * {@code SlaEvaluatorCharacterizationTest} pins the V1 results; this class must keep producing
 * them.
 *
 * <p>Percentile computation belongs in {@code platform-results} rather than in an engine, so
 * every engine's output is summarized identically and two runs remain comparable regardless of
 * which engine produced them.
 */
public final class PercentileCalculator {

    private PercentileCalculator() {
    }

    /**
     * Returns the requested percentile of an already-sorted sample list.
     *
     * <p>The caller sorts because a normalization pass typically computes several percentiles
     * from one sample set; sorting per call would be wasteful and would hide the cost.
     *
     * @param ascendingSamples samples sorted ascending. Not copied and not verified to be
     *                         sorted — an unsorted list yields a meaningless result rather than
     *                         an error, exactly as in V1
     * @param percentile       the percentile to select, 0–100
     * @return the sample at the nearest rank, clamped to the bounds of the list
     * @throws IllegalArgumentException if the list is null or empty, or the percentile is
     *                                  outside 0–100
     */
    public static long fromSorted(List<Long> ascendingSamples, int percentile) {
        if (ascendingSamples == null || ascendingSamples.isEmpty()) {
            throw new IllegalArgumentException("Cannot compute a percentile of an empty sample set");
        }
        if (percentile < 0 || percentile > 100) {
            throw new IllegalArgumentException("Percentile must be between 0 and 100, was " + percentile);
        }

        int size = ascendingSamples.size();
        int index = (int) Math.ceil(percentile / 100.0 * size) - 1;
        return ascendingSamples.get(Math.max(0, Math.min(index, size - 1)));
    }
}
