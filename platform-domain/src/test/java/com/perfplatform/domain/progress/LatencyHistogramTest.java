package com.perfplatform.domain.progress;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The mergeable latency distribution behind every live percentile.
 *
 * <p>The property being protected is the reason this type exists at all: two shards' distributions
 * add exactly, so a merged p95 is read off real counts rather than averaged from two p95s. The tests
 * that matter are therefore the ones showing that adding shards gives the same answer as measuring
 * them together, and that a mismatched contract is refused rather than reconciled.
 */
class LatencyHistogramTest {

    @Nested
    @DisplayName("percentiles")
    class Percentiles {

        @Test
        @DisplayName("an empty histogram reports zero rather than guessing")
        void emptyIsZero() {
            assertThat(LatencyHistogram.empty().percentileMillis(95, 0)).isZero();
        }

        @Test
        @DisplayName("the percentile is the upper edge of the bucket the sample falls in")
        void reportsBucketUpperBound() {
            // 99 samples at 7ms, one at 400ms. p95 lands in the 7ms sample's bucket, whose edge is
            // 8ms — never 7ms, which would claim a precision the buckets do not carry.
            long[] latencies = new long[100];
            java.util.Arrays.fill(latencies, 7L);
            latencies[99] = 400L;

            LatencyHistogram histogram = LatencyHistogram.of(latencies);

            assertThat(histogram.percentileMillis(95, 400)).isEqualTo(8);
            assertThat(histogram.percentileMillis(100, 400)).isEqualTo(500);
        }

        @Test
        @DisplayName("samples past the last bucket report the observed maximum, not infinity")
        void overflowReportsObservedMaximum() {
            LatencyHistogram histogram = LatencyHistogram.of(90_000L, 120_000L);

            // Both are past the 60s edge. "At most 120000ms" is the tightest true statement
            // available; the alternative is a number no reader can act on.
            assertThat(histogram.percentileMillis(99, 120_000)).isEqualTo(120_000);
        }

        @Test
        @DisplayName("a percentile outside 1–100 is refused")
        void rejectsImpossiblePercentile() {
            assertThatThrownBy(() -> LatencyHistogram.of(5L).percentileMillis(0, 5))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("merging")
    class Merging {

        @Test
        @DisplayName("adding two shards gives what measuring them together would have given")
        void mergeIsExact() {
            LatencyHistogram first = LatencyHistogram.of(5L, 5L, 5L, 900L);
            LatencyHistogram second = LatencyHistogram.of(5L, 900L, 900L, 900L);

            LatencyHistogram merged = first.merge(second);
            LatencyHistogram together =
                    LatencyHistogram.of(5L, 5L, 5L, 900L, 5L, 900L, 900L, 900L);

            assertThat(merged.counts()).isEqualTo(together.counts());
            assertThat(merged.totalCount()).isEqualTo(8);
        }

        @Test
        @DisplayName("a merged percentile is not the average of the two, which is the whole point")
        void mergedPercentileIsNotAnAverage() {
            // One fast shard, one slow one. Averaging their p95s would give ~452ms; the merged
            // distribution's p95 is genuinely in the slow bucket, because most of the slow shard's
            // samples are slow.
            long[] fast = new long[100];
            java.util.Arrays.fill(fast, 5L);
            long[] slow = new long[100];
            java.util.Arrays.fill(slow, 900L);

            LatencyHistogram merged = LatencyHistogram.of(fast).merge(LatencyHistogram.of(slow));

            assertThat(merged.percentileMillis(95, 900)).isEqualTo(1_000);
            assertThat(merged.percentileMillis(50, 900)).isEqualTo(5);
        }

        @Test
        @DisplayName("histograms with different bucket edges are refused, never re-bucketed")
        void refusesMismatchedBounds() {
            LatencyHistogram theirs = LatencyHistogram.empty(List.of(10L, 100L));

            assertThat(LatencyHistogram.empty().canMerge(theirs)).isFalse();
            assertThatThrownBy(() -> LatencyHistogram.empty().merge(theirs))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("bucket contract");
        }
    }

    @Nested
    @DisplayName("construction")
    class Construction {

        @Test
        @DisplayName("counts must have one more entry than bounds, for the overflow bucket")
        void requiresOverflowBucket() {
            assertThatThrownBy(() -> new LatencyHistogram(List.of(10L, 100L), List.of(1L, 2L)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("one more count");
        }

        @Test
        @DisplayName("a negative count is refused")
        void rejectsNegativeCount() {
            assertThatThrownBy(() -> new LatencyHistogram(List.of(10L), List.of(-1L, 0L)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
