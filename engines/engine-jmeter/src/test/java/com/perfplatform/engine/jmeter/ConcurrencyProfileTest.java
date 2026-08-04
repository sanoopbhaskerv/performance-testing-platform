package com.perfplatform.engine.jmeter;

import com.perfplatform.domain.spec.PerformanceTestSpecification.LoadStage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The decomposition of a staged profile into thread-group layers.
 *
 * <p>The layers are only correct if their <em>sum</em> reproduces the curve the specification asked
 * for, so most of these tests reconstruct the curve and compare it, rather than asserting a
 * particular set of rows. There is more than one valid decomposition; there is only one valid curve.
 *
 * <p>The arithmetic was checked against a real JMeter with the plugin installed before it was
 * written down here: a two-layer plan produced an observed active-thread count that followed the
 * intended shape through both the rise and the descent.
 */
class ConcurrencyProfileTest {

    private static LoadStage stage(int seconds, int users) {
        return new LoadStage(Duration.ofSeconds(seconds), users);
    }

    /** The curve the layers produce, sampled every second. */
    private static List<Integer> curve(List<ConcurrencyProfile.Layer> layers, int throughSeconds) {
        return java.util.stream.IntStream.rangeClosed(0, throughSeconds)
                .mapToObj(second -> ConcurrencyProfile.concurrencyAt(layers, second))
                .toList();
    }

    @Nested
    @DisplayName("the curve it produces")
    class Curve {

        @Test
        @DisplayName("a single ramp arrives at its target and stays there")
        void singleRamp() {
            var layers = ConcurrencyProfile.layersFor(List.of(stage(60, 100), stage(60, 100)));

            assertThat(ConcurrencyProfile.concurrencyAt(layers, 0)).isZero();
            assertThat(ConcurrencyProfile.concurrencyAt(layers, 30)).isEqualTo(50);
            assertThat(ConcurrencyProfile.concurrencyAt(layers, 60)).isEqualTo(100);
            assertThat(ConcurrencyProfile.concurrencyAt(layers, 100)).isEqualTo(100);
        }

        @Test
        @DisplayName("it never overshoots the profile's peak")
        void neverOvershoots() {
            var layers = ConcurrencyProfile.layersFor(
                    List.of(stage(30, 50), stage(60, 200), stage(60, 200), stage(30, 0)));

            assertThat(curve(layers, 180)).allSatisfy(level -> assertThat(level).isLessThanOrEqualTo(200));
        }

        /**
         * The property that matters for a spike test: the load must come back down to the level
         * that was held before it, not to zero and not to somewhere in between.
         */
        @Test
        @DisplayName("a spike returns to the level it rose from")
        void spikeReturnsToBaseline() {
            var layers = ConcurrencyProfile.layersFor(List.of(
                    stage(60, 50),    // rise to 50
                    stage(60, 50),    // hold
                    stage(30, 300),   // spike
                    stage(30, 50),    // recover
                    stage(60, 50)));  // hold again

            assertThat(ConcurrencyProfile.concurrencyAt(layers, 120)).isEqualTo(50);
            assertThat(ConcurrencyProfile.concurrencyAt(layers, 150)).isEqualTo(300);
            assertThat(ConcurrencyProfile.concurrencyAt(layers, 180)).isEqualTo(50);
            assertThat(ConcurrencyProfile.concurrencyAt(layers, 230)).isEqualTo(50);
        }

        @Test
        @DisplayName("a descent is a ramp, not a cliff")
        void descentIsGradual() {
            var layers = ConcurrencyProfile.layersFor(List.of(stage(60, 100), stage(60, 0)));

            // Halfway down, half the users are gone — a stepwise rendering would show all of them
            // present until the boundary and none after it.
            assertThat(ConcurrencyProfile.concurrencyAt(layers, 90)).isBetween(40, 60);
        }
    }

    @Nested
    @DisplayName("the layers themselves")
    class Layers {

        @Test
        @DisplayName("a rise adds only the difference, because the layers sum")
        void risesCarryTheDifference() {
            var layers = ConcurrencyProfile.layersFor(List.of(stage(60, 100), stage(60, 250)));

            assertThat(layers).extracting(ConcurrencyProfile.Layer::threads)
                    .containsExactly(100, 150);
        }

        @Test
        @DisplayName("a hold adds no layer at all")
        void holdsAddNothing() {
            var layers = ConcurrencyProfile.layersFor(
                    List.of(stage(60, 100), stage(600, 100), stage(600, 100)));

            assertThat(layers).hasSize(1);
        }

        /**
         * Newest-first, and the reason is the story rather than the arithmetic: closing the oldest
         * layer would produce the right total at every instant while claiming the users who arrived
         * for the spike were the ones that stayed.
         */
        @Test
        @DisplayName("a fall removes the newest layers first")
        void fallsRemoveTheNewestFirst() {
            var layers = ConcurrencyProfile.layersFor(List.of(
                    stage(60, 50), stage(30, 300), stage(30, 50), stage(60, 50)));

            var baseline = layers.stream()
                    .filter(layer -> layer.delaySeconds() == 0)
                    .findFirst()
                    .orElseThrow();
            var spike = layers.stream()
                    .filter(layer -> layer.delaySeconds() == 60)
                    .findFirst()
                    .orElseThrow();

            // The spike's layer is the one that shuts down at the recovery; the baseline's runs on.
            assertThat(spike.threads()).isEqualTo(250);
            assertThat(spike.shutdownSeconds()).isEqualTo(30);
            assertThat(baseline.threads()).isEqualTo(50);
            assertThat(baseline.holdSeconds()).isGreaterThan(spike.holdSeconds());
        }

        @Test
        @DisplayName("whatever is still standing at the end stops immediately")
        void trailingLayersStopAtTheEnd() {
            var layers = ConcurrencyProfile.layersFor(List.of(stage(60, 100), stage(300, 100)));

            assertThat(layers).singleElement().satisfies(layer -> {
                assertThat(layer.shutdownSeconds()).isZero();
                // Rise over the first stage, then hold for the second. A ramp-down after the last
                // stage would be load the specification never asked for.
                assertThat(layer.rampSeconds()).isEqualTo(60);
                assertThat(layer.holdSeconds()).isEqualTo(300);
            });
        }
    }

    @Nested
    @DisplayName("shapes that are not really shapes")
    class Degenerate {

        @Test
        @DisplayName("a profile that never leaves zero produces no layers")
        void allZeroIsEmpty() {
            assertThat(ConcurrencyProfile.layersFor(List.of(stage(60, 0), stage(60, 0)))).isEmpty();
        }

        @Test
        @DisplayName("no stages at all produces no layers")
        void noStages() {
            assertThat(ConcurrencyProfile.layersFor(List.of())).isEmpty();
        }

        /**
         * Validation reports a stage with no duration as its own finding. This must not throw first,
         * or that finding would reach the consumer as a 500 from the validate endpoint.
         */
        @Test
        @DisplayName("a stage with no duration is skipped rather than thrown over")
        void skipsMalformedStages() {
            var layers = ConcurrencyProfile.layersFor(java.util.Arrays.asList(
                    new LoadStage(null, 100), stage(60, 50)));

            assertThat(layers).singleElement()
                    .satisfies(layer -> assertThat(layer.threads()).isEqualTo(50));
        }
    }
}
