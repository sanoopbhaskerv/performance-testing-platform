package com.perfplatform.domain.progress;

import com.perfplatform.domain.identity.TestRunId;
import com.perfplatform.domain.results.ObjectiveOutcome;
import com.perfplatform.domain.results.OperationMetrics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every message the live view can say, and the observation each one is pinned to.
 *
 * <p>The rule this file enforces is the one from the plan: no message exists that a test does not
 * tie to a specific measurement. A live view is read by someone deciding whether to stop a running
 * test, and an unpinned message is how a platform ends up telling a novice something confident and
 * wrong.
 */
class ProgressInterpreterTest {

    private static final TestRunId RUN = TestRunId.generate();
    private static final Instant T0 = Instant.parse("2026-08-04T10:00:00Z");
    private static final Duration TOLERANCE = Duration.ofSeconds(45);

    @Test
    @DisplayName("no series says nothing at all")
    void emptySeries() {
        assertThat(ProgressInterpreter.interpret(List.of(), List.of(), T0, TOLERANCE)).isEmpty();
    }

    @Test
    @DisplayName("a healthy run still gets a sentence, because silence reads as 'not looked at'")
    void healthyRunSaysSo() {
        List<ProgressObservation> observations = ProgressInterpreter.interpret(
                List.of(point(T0, 1_000, 5, 25, 20_000, 40)), List.of(), T0, TOLERANCE);

        assertThat(observations).singleElement()
                .satisfies(observation -> {
                    assertThat(observation.code()).isEqualTo(ProgressInterpreter.NOTHING_OF_CONCERN);
                    assertThat(observation.severity()).isEqualTo(ProgressObservation.Severity.INFO);
                    assertThat(observation.evidence()).contains("1000 requests measured");
                });
    }

    @Test
    @DisplayName("every observation carries the numbers behind it")
    void everyObservationCarriesEvidence() {
        List<ProgressObservation> observations = ProgressInterpreter.interpret(
                List.of(point(T0, 1_000, 300, 25, 20_000, 40)), List.of(), T0, TOLERANCE);

        assertThat(observations).isNotEmpty()
                .allSatisfy(observation -> assertThat(observation.evidence()).isNotBlank());
    }

    @Nested
    @DisplayName("a generator that went quiet")
    class Silence {

        @Test
        @DisplayName("a shard past the tolerance is reported as lost, not as a slowdown")
        void reportsLostShard() {
            LiveProgress progress = twoShards(T0, T0.plusSeconds(100));

            List<ProgressObservation> observations = ProgressInterpreter.interpret(
                    List.of(progress), List.of(), T0.plusSeconds(101), TOLERANCE);

            assertThat(observations).anySatisfy(observation -> {
                assertThat(observation.code()).isEqualTo(ProgressInterpreter.RUNNER_SILENT);
                assertThat(observation.severity())
                        .isEqualTo(ProgressObservation.Severity.CRITICAL);
                assertThat(observation.evidence())
                        .contains("Shard 0 last reported 101 seconds ago");
                // The message has to say why the totals still look fine, or a reader concludes the
                // warning is spurious and dismisses it.
                assertThat(observation.message()).contains("still rising");
            });
        }

        @Test
        @DisplayName("a shard within the tolerance is not reported")
        void quietWithinToleranceIsFine() {
            LiveProgress progress = twoShards(T0.plusSeconds(90), T0.plusSeconds(100));

            assertThat(ProgressInterpreter.interpret(
                    List.of(progress), List.of(), T0.plusSeconds(101), TOLERANCE))
                    .noneMatch(o -> o.code().equals(ProgressInterpreter.RUNNER_SILENT));
        }

        @Test
        @DisplayName("a shard that has never reported is called out separately")
        void missingShardReported() {
            LiveProgress progress = new LiveProgress(RUN, "jmeter", T0, T0, 2, 60, 25,
                    overall(500, 0, 10_000, 40), Map.of(),
                    List.of(new LiveProgress.ShardReport(0, T0, 500, 0, 25)));

            assertThat(ProgressInterpreter.interpret(List.of(progress), List.of(), T0, TOLERANCE))
                    .anySatisfy(observation -> {
                        assertThat(observation.code())
                                .isEqualTo(ProgressInterpreter.SHARDS_NOT_REPORTING);
                        assertThat(observation.evidence()).contains("1 of 2 shards");
                    });
        }
    }

    @Nested
    @DisplayName("errors")
    class Errors {

        @Test
        @DisplayName("5% is elevated")
        void elevated() {
            assertThat(codes(point(T0, 1_000, 60, 25, 20_000, 40)))
                    .contains(ProgressInterpreter.ERROR_RATE_ELEVATED);
        }

        @Test
        @DisplayName("25% is critical, and the message says fast failures flatter the latency")
        void critical() {
            List<ProgressObservation> observations = ProgressInterpreter.interpret(
                    List.of(point(T0, 1_000, 300, 25, 20_000, 40)), List.of(), T0, TOLERANCE);

            assertThat(observations).anySatisfy(observation -> {
                assertThat(observation.code()).isEqualTo(ProgressInterpreter.ERROR_RATE_CRITICAL);
                assertThat(observation.severity())
                        .isEqualTo(ProgressObservation.Severity.CRITICAL);
                assertThat(observation.message()).contains("flattering");
                assertThat(observation.evidence()).contains("30% of 1000 requests");
            });
        }

        @Test
        @DisplayName("a handful of samples is not an error rate")
        void tooFewSamples() {
            assertThat(codes(point(T0, 10, 5, 25, 500, 40)))
                    .doesNotContain(ProgressInterpreter.ERROR_RATE_ELEVATED,
                            ProgressInterpreter.ERROR_RATE_CRITICAL);
        }

        @Test
        @DisplayName("failures concentrated in the last window are reported before the cumulative rate moves")
        void risingBeforeCumulativeMoves() {
            // 4,000 clean requests, then 200 of which 80 failed. Cumulative is 1.9% — under every
            // ceiling — while the system is currently failing 40% of what it is asked.
            LiveProgress before = point(T0, 4_000, 0, 25, 20_000, 40);
            LiveProgress now = point(T0.plusSeconds(5), 4_200, 80, 25, 20_000, 40);

            List<ProgressObservation> observations = ProgressInterpreter.interpret(
                    List.of(before, now), List.of(), T0.plusSeconds(5), TOLERANCE);

            assertThat(observations).anySatisfy(observation -> {
                assertThat(observation.code()).isEqualTo(ProgressInterpreter.ERRORS_RISING);
                assertThat(observation.evidence())
                        .contains("40% of the most recent 200 requests failed");
            });
            assertThat(codes(now)).doesNotContain(ProgressInterpreter.ERROR_RATE_ELEVATED);
        }
    }

    @Nested
    @DisplayName("trends")
    class Trends {

        @Test
        @DisplayName("a p95 that has doubled is called out with both figures")
        void latencyKnee() {
            LiveProgress before = withLatency(T0, 100, LatencyHistogram.of(filled(100, 40)));
            LiveProgress now = withLatency(T0.plusSeconds(60), 400,
                    LatencyHistogram.of(filled(400, 400)));

            assertThat(ProgressInterpreter.interpret(
                    List.of(before, now), List.of(), T0.plusSeconds(60), TOLERANCE))
                    .anySatisfy(observation -> {
                        assertThat(observation.code())
                                .isEqualTo(ProgressInterpreter.LATENCY_DEGRADING);
                        assertThat(observation.evidence())
                                .contains("p95 is now about 500ms, against about 50ms");
                    });
        }

        @Test
        @DisplayName("latency that has not moved says nothing")
        void steadyLatencyIsQuiet() {
            LiveProgress before = withLatency(T0, 100, LatencyHistogram.of(filled(100, 40)));
            LiveProgress now = withLatency(T0.plusSeconds(60), 400,
                    LatencyHistogram.of(filled(400, 45)));

            assertThat(ProgressInterpreter.interpret(
                    List.of(before, now), List.of(), T0.plusSeconds(60), TOLERANCE))
                    .noneMatch(o -> o.code().equals(ProgressInterpreter.LATENCY_DEGRADING));
        }

        @Test
        @DisplayName("more users and no more throughput is reported as saturation, at INFO")
        void plateau() {
            LiveProgress before = point(T0, 2_000, 0, 20, 40_000, 40);
            LiveProgress now = point(T0.plusSeconds(60), 6_000, 0, 40, 41_000, 40);

            assertThat(ProgressInterpreter.interpret(
                    List.of(before, now), List.of(), T0.plusSeconds(60), TOLERANCE))
                    .anySatisfy(observation -> {
                        assertThat(observation.code())
                                .isEqualTo(ProgressInterpreter.THROUGHPUT_PLATEAU);
                        assertThat(observation.severity())
                                .isEqualTo(ProgressObservation.Severity.INFO);
                        assertThat(observation.evidence())
                                .contains("Active users rose 100% while throughput moved 2%");
                    });
        }

        @Test
        @DisplayName("throughput that grew with the users is not a plateau")
        void scalingIsNotAPlateau() {
            LiveProgress before = point(T0, 2_000, 0, 20, 40_000, 40);
            LiveProgress now = point(T0.plusSeconds(60), 6_000, 0, 40, 78_000, 40);

            assertThat(ProgressInterpreter.interpret(
                    List.of(before, now), List.of(), T0.plusSeconds(60), TOLERANCE))
                    .noneMatch(o -> o.code().equals(ProgressInterpreter.THROUGHPUT_PLATEAU));
        }
    }

    @Nested
    @DisplayName("nothing measured")
    class NothingMeasured {

        @Test
        @DisplayName("the first minute with no samples is warm-up")
        void warmingUp() {
            assertThat(codes(empty(T0, 20))).contains(ProgressInterpreter.WARMING_UP);
        }

        @Test
        @DisplayName("past a minute with no samples, the run is measuring nothing and says so")
        void noLoad() {
            List<ProgressObservation> observations = ProgressInterpreter.interpret(
                    List.of(empty(T0, 120)), List.of(), T0, TOLERANCE);

            assertThat(observations).anySatisfy(observation -> {
                assertThat(observation.code()).isEqualTo(ProgressInterpreter.NO_LOAD_OBSERVED);
                assertThat(observation.severity())
                        .isEqualTo(ProgressObservation.Severity.CRITICAL);
                assertThat(observation.evidence()).contains("120 seconds elapsed with no samples");
            });
        }
    }

    @Nested
    @DisplayName("objectives")
    class Objectives {

        @Test
        @DisplayName("a breaching live objective is reported, labelled as not the run's verdict")
        void breachIsProvisional() {
            ObjectiveOutcome breach = new ObjectiveOutcome("http.response.time@*",
                    "http.response.time", "*", ObjectiveOutcome.Verdict.BREACH, 900, 500,
                    "p95 was 900ms against a ceiling of 500ms.");

            List<ProgressObservation> observations = ProgressInterpreter.interpret(
                    List.of(point(T0, 1_000, 0, 25, 20_000, 40)), List.of(breach), T0, TOLERANCE);

            assertThat(observations).anySatisfy(observation -> {
                assertThat(observation.code()).isEqualTo(ProgressInterpreter.OBJECTIVE_AT_RISK);
                assertThat(observation.evidence()).contains("not the run's verdict");
            });
        }

        @Test
        @DisplayName("objectives that are being met say nothing")
        void passingObjectivesAreQuiet() {
            ObjectiveOutcome pass = new ObjectiveOutcome("http.response.time@*",
                    "http.response.time", "*", ObjectiveOutcome.Verdict.PASS, 120, 500, "fine");

            assertThat(ProgressInterpreter.interpret(
                    List.of(point(T0, 1_000, 0, 25, 20_000, 40)), List.of(pass), T0, TOLERANCE))
                    .noneMatch(o -> o.code().equals(ProgressInterpreter.OBJECTIVE_AT_RISK));
        }
    }

    @Test
    @DisplayName("the most severe observation comes first")
    void mostSevereFirst() {
        LiveProgress progress = new LiveProgress(RUN, "jmeter", T0, T0, 2, 60, 25,
                overall(1_000, 300, 20_000, 40), Map.of(),
                List.of(new LiveProgress.ShardReport(0, T0, 1_000, 300, 25)));

        List<ProgressObservation> observations =
                ProgressInterpreter.interpret(List.of(progress), List.of(), T0, TOLERANCE);

        assertThat(observations.get(0).severity()).isEqualTo(ProgressObservation.Severity.CRITICAL);
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private static List<String> codes(LiveProgress progress) {
        return ProgressInterpreter.interpret(List.of(progress), List.of(),
                        progress.capturedAt(), TOLERANCE).stream()
                .map(ProgressObservation::code)
                .toList();
    }

    private static long[] filled(int count, long latencyMillis) {
        long[] values = new long[count];
        java.util.Arrays.fill(values, latencyMillis);
        return values;
    }

    private static ProgressMetrics overall(long samples, long errors, long throughputMilli,
            long latencyMillis) {
        return new ProgressMetrics(OperationMetrics.ALL, samples, errors, 0,
                latencyMillis, latencyMillis, latencyMillis, throughputMilli,
                LatencyHistogram.of(filled((int) samples, latencyMillis)));
    }

    /** One point in the series, with one shard reporting on time. */
    private static LiveProgress point(Instant capturedAt, long samples, long errors,
            long activeUsers, long throughputMilli, long latencyMillis) {
        return new LiveProgress(RUN, "jmeter", capturedAt, capturedAt, 1, 60, activeUsers,
                overall(samples, errors, throughputMilli, latencyMillis), Map.of(),
                List.of(new LiveProgress.ShardReport(0, capturedAt, samples, errors, activeUsers)));
    }

    private static LiveProgress withLatency(Instant capturedAt, long samples,
            LatencyHistogram latency) {
        ProgressMetrics metrics = new ProgressMetrics(OperationMetrics.ALL, samples, 0, 0,
                1, 5_000, 100, 20_000, latency);
        return new LiveProgress(RUN, "jmeter", capturedAt, capturedAt, 1, 60, 25, metrics, Map.of(),
                List.of(new LiveProgress.ShardReport(0, capturedAt, samples, 0, 25)));
    }

    private static LiveProgress empty(Instant capturedAt, long elapsedSeconds) {
        return new LiveProgress(RUN, "jmeter", capturedAt, capturedAt, 1, elapsedSeconds, 25,
                ProgressMetrics.empty(OperationMetrics.ALL), Map.of(),
                List.of(new LiveProgress.ShardReport(0, capturedAt, 0, 0, 25)));
    }

    private static LiveProgress twoShards(Instant firstCapturedAt, Instant secondCapturedAt) {
        return new LiveProgress(RUN, "jmeter", secondCapturedAt, secondCapturedAt, 2, 120, 50,
                overall(1_400, 0, 24_000, 40), Map.of(),
                List.of(new LiveProgress.ShardReport(0, firstCapturedAt, 500, 0, 25),
                        new LiveProgress.ShardReport(1, secondCapturedAt, 900, 0, 25)));
    }
}
