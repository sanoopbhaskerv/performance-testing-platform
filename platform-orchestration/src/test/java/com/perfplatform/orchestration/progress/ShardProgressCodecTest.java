package com.perfplatform.orchestration.progress;

import com.perfplatform.domain.identity.RunnerShardId;
import com.perfplatform.domain.identity.TestRunId;
import com.perfplatform.domain.progress.LatencyHistogram;
import com.perfplatform.domain.progress.ProgressMetrics;
import com.perfplatform.domain.progress.ShardProgress;
import com.perfplatform.domain.progress.ShardProgressJson;
import com.perfplatform.domain.results.OperationMetrics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reading what a runner published.
 *
 * <p>The important test here is the fixture one. {@code jmeter-runner-progress.json} is not written
 * by hand — it is the actual output of {@code runner-images/jmeter-runner/progress.py} run over a
 * results file, captured verbatim. The progress document has three independent implementations by
 * necessity (Python in the JMeter image, Node in the Playwright image, Java in the local provider),
 * and a fixture produced by a real one is what keeps this decoder honest about the contract rather
 * than about its own idea of it.
 *
 * <p>The rest is about not breaking a run. Every unreadable input has to produce empty, because a
 * reconciliation pass that threw while reading an object a runner was mid-way through overwriting
 * would be a run that could not reach a terminal state.
 */
class ShardProgressCodecTest {

    private final ShardProgressCodec codec = new ShardProgressCodec();

    @Nested
    @DisplayName("the JMeter runner's real output")
    class RunnerFixture {

        @Test
        @DisplayName("decodes, with the counts, the window and the active users the reducer wrote")
        void decodesFixture() throws IOException {
            ShardProgress progress = codec.decode(fixture()).orElseThrow();

            assertThat(progress.runId())
                    .isEqualTo(TestRunId.of("3f6b1f5e-1c2d-4b8a-9f11-0a2b3c4d5e6f"));
            assertThat(progress.shardId())
                    .isEqualTo(RunnerShardId.of("8a1c2d3e-4f50-4a6b-8c9d-0e1f2a3b4c5d"));
            assertThat(progress.shardIndex()).isZero();
            assertThat(progress.shardCount()).isEqualTo(2);
            assertThat(progress.engine()).isEqualTo("jmeter");
            assertThat(progress.capturedAt()).isEqualTo(Instant.parse("2026-08-04T10:01:05Z"));
            assertThat(progress.elapsedSeconds()).isEqualTo(65);
            assertThat(progress.windowSeconds()).isEqualTo(5);
            assertThat(progress.activeUsers()).isEqualTo(26);

            assertThat(progress.overall().sampleCount()).isEqualTo(5);
            assertThat(progress.overall().errorCount()).isEqualTo(1);
            // The row with a non-numeric elapsed time. Counted rather than dropped, and kept in the
            // error-rate denominator: a denominator that excluded it would report a better error
            // rate the worse the runner's output got.
            assertThat(progress.overall().unparsedCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("percentiles come from the buckets the runner reported, not from a number it stated")
        void percentilesFromBuckets() throws IOException {
            ShardProgress progress = codec.decode(fixture()).orElseThrow();

            OperationMetrics overall = progress.overallAsOperationMetrics();

            // Samples at 38, 42, 55, 410 and 9001ms. Every percentile is the upper edge of the
            // bucket its sample sits in — "at most", never the sample value itself. The median
            // sample is 55ms, which is in the 75ms bucket; the slowest is 9001ms, in the 10s one.
            // Resolution is the price of being able to add shards together exactly.
            assertThat(overall.p50Millis()).isEqualTo(75);
            assertThat(overall.p99Millis()).isEqualTo(10_000);
        }

        @Test
        @DisplayName("operations arrive separately, keyed as the specification labels them")
        void keepsOperations() throws IOException {
            ShardProgress progress = codec.decode(fixture()).orElseThrow();

            assertThat(progress.byOperation()).containsOnlyKeys("checkout/browse", "checkout/pay");
            assertThat(progress.byOperation().get("checkout/pay").errorCount()).isEqualTo(1);
        }

        private byte[] fixture() throws IOException {
            try (InputStream stream = getClass()
                    .getResourceAsStream("/progress/jmeter-runner-progress.json")) {
                return stream.readAllBytes();
            }
        }
    }

    @Nested
    @DisplayName("round trip with the control plane's own writer")
    class RoundTrip {

        @Test
        @DisplayName("what the local provider writes is what this reads")
        void roundTrips() {
            ShardProgress written = new ShardProgress(
                    TestRunId.generate(), RunnerShardId.generate(), 1, 2, "jmeter",
                    "sha256:abc", Instant.parse("2026-08-04T10:00:00Z"), 120, 5, 40,
                    new ProgressMetrics(OperationMetrics.ALL, 300, 12, 2, 4, 800, 90, 21_400,
                            LatencyHistogram.of(4, 90, 800, 90)),
                    Map.of("checkout/pay \"promo\"", new ProgressMetrics("checkout/pay \"promo\"",
                            100, 12, 0, 4, 800, 120, 7_000, LatencyHistogram.of(800))));

            ShardProgress read = codec.decode(
                    ShardProgressJson.write(written).getBytes(StandardCharsets.UTF_8))
                    .orElseThrow();

            assertThat(read).isEqualTo(written);
        }

        @Test
        @DisplayName("an operation name containing a quote survives, rather than breaking the document")
        void escapesOperationNames() {
            // A scenario name is written by a consumer, so it can contain anything. An unescaped
            // quote would produce a document the control plane cannot parse — which presents as a
            // run whose live view mysteriously never appears.
            ShardProgress written = new ShardProgress(
                    TestRunId.generate(), RunnerShardId.generate(), 0, 1, "jmeter", "",
                    Instant.parse("2026-08-04T10:00:00Z"), 10, 5, 1,
                    ProgressMetrics.empty(OperationMetrics.ALL),
                    Map.of("orders/say \"hello\"\\now", ProgressMetrics.empty("x")));

            ShardProgress read = codec.decode(
                    ShardProgressJson.write(written).getBytes(StandardCharsets.UTF_8))
                    .orElseThrow();

            assertThat(read.byOperation()).containsKey("orders/say \"hello\"\\now");
        }
    }

    @Nested
    @DisplayName("refusing what it cannot read")
    class Refusals {

        @Test
        @DisplayName("a truncated object is skipped rather than thrown over")
        void truncated() {
            // The expected case, not an incident: the object is being overwritten every few seconds
            // and a read can land mid-write.
            String whole = ShardProgressJson.write(new ShardProgress(
                    TestRunId.generate(), RunnerShardId.generate(), 0, 1, "jmeter", "",
                    Instant.parse("2026-08-04T10:00:00Z"), 10, 5, 1,
                    ProgressMetrics.empty(OperationMetrics.ALL), Map.of()));

            byte[] half = whole.substring(0, whole.length() / 2).getBytes(StandardCharsets.UTF_8);

            assertThat(codec.decode(half)).isEmpty();
        }

        @Test
        @DisplayName("a document declaring an unknown schema is skipped, never read as this one")
        void unknownSchema() {
            String document = """
                    {"schema":"perfplatform.progress/v99","runId":"%s","shardId":"%s",
                     "shardIndex":0,"shardCount":1,"engine":"jmeter","planDigest":"",
                     "capturedAt":"2026-08-04T10:00:00Z","overall":{}}"""
                    .formatted(TestRunId.generate().value(), RunnerShardId.generate().value());

            assertThat(codec.decode(document.getBytes(StandardCharsets.UTF_8))).isEmpty();
        }

        @Test
        @DisplayName("bucket counts that do not match their bounds are refused, not re-bucketed")
        void mismatchedBuckets() {
            String document = """
                    {"schema":"perfplatform.progress/v1","runId":"%s","shardId":"%s",
                     "shardIndex":0,"shardCount":1,"engine":"jmeter","planDigest":"",
                     "capturedAt":"2026-08-04T10:00:00Z","elapsedSeconds":1,"windowSeconds":1,
                     "activeUsers":1,
                     "overall":{"sampleCount":1,"errorCount":0,"unparsedCount":0,"minMillis":1,
                       "maxMillis":1,"meanMillis":1,"throughputPerSecondMilli":1000,
                       "latencyBucketBoundsMillis":[10,100],"latencyBucketCounts":[1]},
                     "byOperation":{}}"""
                    .formatted(TestRunId.generate().value(), RunnerShardId.generate().value());

            assertThat(codec.decode(document.getBytes(StandardCharsets.UTF_8))).isEmpty();
        }

        @Test
        @DisplayName("nothing at all is empty rather than an exception")
        void nothing() {
            assertThat(codec.decode(null)).isEmpty();
            assertThat(codec.decode(new byte[0])).isEmpty();
            assertThat(codec.decode("not json".getBytes(StandardCharsets.UTF_8))).isEmpty();
        }

        @Test
        @DisplayName("an object larger than the bound is refused before it is parsed")
        void oversized() {
            byte[] huge = new byte[ShardProgressCodec.MAX_DOCUMENT_BYTES + 1];

            assertThat(codec.decode(huge)).isEmpty();
        }
    }

    @Nested
    @DisplayName("identity")
    class Identity {

        @Test
        @DisplayName("a snapshot for another plan does not describe this run")
        void refusesAnotherPlan() {
            TestRunId run = TestRunId.generate();
            ShardProgress snapshot = new ShardProgress(run, RunnerShardId.generate(), 0, 1,
                    "jmeter", "sha256:other", Instant.parse("2026-08-04T10:00:00Z"), 1, 1, 1,
                    ProgressMetrics.empty(OperationMetrics.ALL), Map.of());

            assertThat(snapshot.describes(run, "sha256:expected")).isFalse();
        }

        @Test
        @DisplayName("a snapshot with no digest is accepted: it said nothing, not something wrong")
        void toleratesMissingDigest() {
            TestRunId run = TestRunId.generate();
            ShardProgress snapshot = new ShardProgress(run, RunnerShardId.generate(), 0, 1,
                    "jmeter", "", Instant.parse("2026-08-04T10:00:00Z"), 1, 1, 1,
                    ProgressMetrics.empty(OperationMetrics.ALL), Map.of());

            assertThat(snapshot.describes(run, "sha256:expected")).isTrue();
        }

        @Test
        @DisplayName("a snapshot for another run does not describe this one")
        void refusesAnotherRun() {
            ShardProgress snapshot = new ShardProgress(TestRunId.generate(),
                    RunnerShardId.generate(), 0, 1, "jmeter", "sha256:expected",
                    Instant.parse("2026-08-04T10:00:00Z"), 1, 1, 1,
                    ProgressMetrics.empty(OperationMetrics.ALL), Map.of());

            assertThat(snapshot.describes(TestRunId.generate(), "sha256:expected")).isFalse();
        }
    }

    @Test
    @DisplayName("an empty overall block yields nothing, rather than a snapshot claiming zero traffic")
    void emptyOverallIsNotZeroTraffic() {
        String document = """
                {"schema":"perfplatform.progress/v1","runId":"%s","shardId":"%s",
                 "shardIndex":0,"shardCount":1,"engine":"jmeter","planDigest":"",
                 "capturedAt":"2026-08-04T10:00:00Z","overall":{},"byOperation":{}}"""
                .formatted(TestRunId.generate().value(), RunnerShardId.generate().value());

        Optional<ShardProgress> decoded = codec.decode(document.getBytes(StandardCharsets.UTF_8));

        assertThat(decoded).isEmpty();
    }
}
