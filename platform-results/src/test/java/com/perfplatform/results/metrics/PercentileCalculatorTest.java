package com.perfplatform.results.metrics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the nearest-rank percentile algorithm extracted from V1's {@code SlaEvaluator}.
 *
 * <p>The values asserted here are the same ones
 * {@code SlaEvaluatorCharacterizationTest} asserts through the V1 code path. If these two
 * diverge, V1 SLA evaluation and V2 objective evaluation would disagree about the same run.
 */
class PercentileCalculatorTest {

    private static List<Long> ascending(int count) {
        return LongStream.rangeClosed(1, count).map(i -> i * 10).boxed().toList();
    }

    @ParameterizedTest(name = "p{0} of 10 samples (10..100ms) is {1}ms")
    @CsvSource({
            "0, 10",
            "50, 50",
            "90, 90",
            "95, 100",
            "99, 100",
            "100, 100"
    })
    @DisplayName("nearest-rank selection matches the V1 algorithm")
    void selectsNearestRank(int percentile, long expected) {
        assertThat(PercentileCalculator.fromSorted(ascending(10), percentile)).isEqualTo(expected);
    }

    @Test
    @DisplayName("a single sample is returned for every percentile")
    void handlesSingleSample() {
        List<Long> single = List.of(42L);

        assertThat(PercentileCalculator.fromSorted(single, 50)).isEqualTo(42L);
        assertThat(PercentileCalculator.fromSorted(single, 99)).isEqualTo(42L);
        assertThat(PercentileCalculator.fromSorted(single, 0)).isEqualTo(42L);
    }

    @Test
    @DisplayName("p99 of 100 samples selects the 99th, not the last")
    void distinguishesHighPercentilesAtScale() {
        List<Long> samples = ascending(100);

        assertThat(PercentileCalculator.fromSorted(samples, 99)).isEqualTo(990L);
        assertThat(PercentileCalculator.fromSorted(samples, 100)).isEqualTo(1000L);
    }

    @Test
    @DisplayName("the index is clamped so p100 never runs off the end of the list")
    void clampsToListBounds() {
        List<Long> samples = ascending(7);

        assertThat(PercentileCalculator.fromSorted(samples, 100)).isEqualTo(70L);
    }

    @Test
    @DisplayName("an empty or null sample set is rejected rather than returning zero")
    void rejectsEmptySampleSet() {
        assertThatThrownBy(() -> PercentileCalculator.fromSorted(List.of(), 95))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty sample set");

        assertThatThrownBy(() -> PercentileCalculator.fromSorted(null, 95))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty sample set");
    }

    @Test
    @DisplayName("a percentile outside 0-100 is rejected")
    void rejectsOutOfRangePercentile() {
        assertThatThrownBy(() -> PercentileCalculator.fromSorted(ascending(10), 101))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 0 and 100");

        assertThatThrownBy(() -> PercentileCalculator.fromSorted(ascending(10), -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 0 and 100");
    }

    @Test
    @DisplayName("the caller's list is not mutated")
    void doesNotMutateInput() {
        List<Long> samples = new ArrayList<>(ascending(5));
        List<Long> before = List.copyOf(samples);

        PercentileCalculator.fromSorted(samples, 95);

        assertThat(samples).isEqualTo(before);
    }
}
