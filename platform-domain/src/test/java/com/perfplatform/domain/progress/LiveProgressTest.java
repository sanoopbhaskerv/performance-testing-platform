package com.perfplatform.domain.progress;

import com.perfplatform.domain.identity.RunnerShardId;
import com.perfplatform.domain.identity.TestRunId;
import com.perfplatform.domain.results.NormalizedRunResult;
import com.perfplatform.domain.results.OperationMetrics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Merging shard snapshots into one live view.
 *
 * <p>Two behaviours are worth more than the arithmetic. A shard that stopped publishing must stay
 * visible after the merge — added totals hide it perfectly otherwise. And a merge that is missing a
 * shard must produce indeterminate objectives rather than a pass, because it describes less load
 * than the plan asks for; that is the same rule the final analysis applies, deliberately reused
 * rather than restated.
 */
class LiveProgressTest {

    private static final TestRunId RUN = TestRunId.generate();
    private static final Instant T0 = Instant.parse("2026-08-04T10:00:00Z");

    @Test
    @DisplayName("no snapshots is empty, not a view full of zeros")
    void noSnapshotsIsEmpty() {
        assertThat(LiveProgress.merge(List.of(), 1, T0)).isEmpty();
    }

    @Nested
    @DisplayName("merging shards")
    class Merging {

        @Test
        @DisplayName("counts add, throughput adds and active users add")
        void addsAcrossShards() {
            LiveProgress merged = LiveProgress.merge(
                    List.of(shard(0, T0, 100, 2, 25, 10_000),
                            shard(1, T0.plusSeconds(2), 140, 8, 25, 14_000)),
                    2, T0.plusSeconds(3)).orElseThrow();

            assertThat(merged.overall().sampleCount()).isEqualTo(240);
            assertThat(merged.overall().errorCount()).isEqualTo(10);
            assertThat(merged.overall().throughputPerSecondMilli()).isEqualTo(24_000);
            assertThat(merged.activeUsers()).isEqualTo(50);
            assertThat(merged.shardsReporting()).isEqualTo(2);
            assertThat(merged.isComplete()).isTrue();
        }

        @Test
        @DisplayName("capturedAt is the freshest shard's, so staleness is measured against the newest")
        void capturedAtIsFreshest() {
            LiveProgress merged = LiveProgress.merge(
                    List.of(shard(0, T0, 100, 0, 10, 5_000),
                            shard(1, T0.plusSeconds(30), 100, 0, 10, 5_000)),
                    2, T0.plusSeconds(31)).orElseThrow();

            assertThat(merged.capturedAt()).isEqualTo(T0.plusSeconds(30));
        }

        @Test
        @DisplayName("a shard that stopped publishing is still visible after the merge")
        void silentShardStaysVisible() {
            // The totals still climb — the live shard keeps adding to them — which is exactly why
            // the per-shard capture time has to survive the merge.
            LiveProgress merged = LiveProgress.merge(
                    List.of(shard(0, T0, 500, 0, 25, 12_000),
                            shard(1, T0.plusSeconds(120), 900, 0, 25, 12_000)),
                    2, T0.plusSeconds(121)).orElseThrow();

            LiveProgress.ShardReport stalest = merged.stalestShard().orElseThrow();

            assertThat(stalest.shardIndex()).isZero();
            assertThat(stalest.ageSeconds(T0.plusSeconds(121))).isEqualTo(121);
        }

        @Test
        @DisplayName("shards are ordered by index whatever order they arrived in")
        void ordersByShardIndex() {
            LiveProgress merged = LiveProgress.merge(
                    List.of(shard(2, T0, 1, 0, 1, 1_000),
                            shard(0, T0, 1, 0, 1, 1_000),
                            shard(1, T0, 1, 0, 1, 1_000)),
                    3, T0).orElseThrow();

            assertThat(merged.shards()).extracting(LiveProgress.ShardReport::shardIndex)
                    .containsExactly(0, 1, 2);
        }
    }

    @Nested
    @DisplayName("as a normalized result")
    class AsNormalized {

        @Test
        @DisplayName("percentiles come from the merged distribution, not from any one shard")
        void percentilesFromMergedDistribution() {
            ProgressMetrics fast = metrics(OperationMetrics.ALL, 100, 0, 10_000,
                    LatencyHistogram.of(filled(100, 5)));
            ProgressMetrics slow = metrics(OperationMetrics.ALL, 100, 0, 10_000,
                    LatencyHistogram.of(filled(100, 900)));

            LiveProgress merged = LiveProgress.merge(
                    List.of(snapshot(0, T0, fast, Map.of()),
                            snapshot(1, T0, slow, Map.of())),
                    2, T0).orElseThrow();

            NormalizedRunResult result = merged.asNormalizedResult();

            assertThat(result.overall().p50Millis()).isEqualTo(5);
            assertThat(result.overall().p95Millis()).isEqualTo(1_000);
            assertThat(result.overall().sampleCount()).isEqualTo(200);
        }

        @Test
        @DisplayName("a shard that has not reported makes the result incomplete, so nothing can pass")
        void missingShardIsIncomplete() {
            LiveProgress merged = LiveProgress.merge(
                    List.of(shard(0, T0, 100, 0, 25, 10_000)), 2, T0).orElseThrow();

            NormalizedRunResult result = merged.asNormalizedResult();

            assertThat(result.shardsReported()).isEqualTo(1);
            assertThat(result.shardsExpected()).isEqualTo(2);
            assertThat(result.isComplete()).isFalse();
        }

        @Test
        @DisplayName("per-operation metrics survive the merge under their own names")
        void keepsOperations() {
            ProgressMetrics browse = metrics("checkout/browse", 60, 0, 6_000,
                    LatencyHistogram.of(filled(60, 30)));
            ProgressMetrics pay = metrics("checkout/pay", 40, 4, 4_000,
                    LatencyHistogram.of(filled(40, 300)));

            LiveProgress merged = LiveProgress.merge(
                    List.of(snapshot(0, T0, metrics(OperationMetrics.ALL, 100, 4, 10_000,
                                    LatencyHistogram.of(filled(60, 30)).merge(
                                            LatencyHistogram.of(filled(40, 300)))),
                            Map.of("checkout/browse", browse, "checkout/pay", pay))),
                    1, T0).orElseThrow();

            assertThat(merged.byOperation()).containsOnlyKeys("checkout/browse", "checkout/pay");
            assertThat(merged.asNormalizedResult().operation("checkout/pay").orElseThrow()
                    .errorCount()).isEqualTo(4);
        }
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private static long[] filled(int count, long latencyMillis) {
        long[] values = new long[count];
        java.util.Arrays.fill(values, latencyMillis);
        return values;
    }

    private static ProgressMetrics metrics(String name, long samples, long errors,
            long throughputMilli, LatencyHistogram latency) {
        return new ProgressMetrics(name, samples, errors, 0, 1, 900, 50, throughputMilli, latency);
    }

    private static ShardProgress snapshot(int index, Instant capturedAt, ProgressMetrics overall,
            Map<String, ProgressMetrics> byOperation) {
        return new ShardProgress(RUN, RunnerShardId.generate(), index, Math.max(2, index + 1),
                "jmeter", "sha256:abc", capturedAt, 60, 5, 25, overall, byOperation);
    }

    private static ShardProgress shard(int index, Instant capturedAt, long samples, long errors,
            long activeUsers, long throughputMilli) {
        return new ShardProgress(RUN, RunnerShardId.generate(), index, Math.max(2, index + 1),
                "jmeter", "sha256:abc", capturedAt, 60, 5, activeUsers,
                new ProgressMetrics(OperationMetrics.ALL, samples, errors, 0, 1, 900, 50,
                        throughputMilli, LatencyHistogram.of(filled((int) samples, 50))),
                Map.of());
    }
}
