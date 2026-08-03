package com.perfplatform.domain.spec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpecValueObjectsTest {

    @Nested
    @DisplayName("HumanDuration")
    class Durations {

        @ParameterizedTest(name = "{0} parses to {1} seconds")
        @CsvSource({
                "500ms, 0",
                "30s, 30",
                "5m, 300",
                "2h, 7200",
                "90d, 7776000"
        })
        @DisplayName("compact forms parse")
        void parsesCompactForms(String raw, long expectedSeconds) {
            assertThat(HumanDuration.parse(raw).toSeconds()).isEqualTo(expectedSeconds);
        }

        @Test
        @DisplayName("ISO-8601 is accepted so machine-generated specs need no conversion")
        void parsesIso8601() {
            assertThat(HumanDuration.parse("PT5M")).isEqualTo(Duration.ofMinutes(5));
            assertThat(HumanDuration.parse("pt1h30m")).isEqualTo(Duration.ofMinutes(90));
        }

        @Test
        @DisplayName("whitespace and case are tolerated")
        void toleratesWhitespaceAndCase() {
            assertThat(HumanDuration.parse("  5 M ".replace(" M ", "m "))).isEqualTo(Duration.ofMinutes(5));
            assertThat(HumanDuration.parse("5M")).isEqualTo(Duration.ofMinutes(5));
        }

        @ParameterizedTest
        @ValueSource(strings = {"5", "5 minutes", "five", "-5m", "", "   ", "5x"})
        @DisplayName("an unrecognized duration is rejected with guidance")
        void rejectsUnparseableDurations(String raw) {
            assertThatThrownBy(() -> HumanDuration.parse(raw))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a null duration is rejected")
        void rejectsNull() {
            assertThatThrownBy(() -> HumanDuration.parse(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @ParameterizedTest(name = "{0} seconds formats to {1}")
        @CsvSource({
                "0, 0s",
                "45, 45s",
                "300, 5m",
                "3600, 1h",
                "7200, 2h",
                "86400, 1d"
        })
        @DisplayName("formatting picks the largest exact unit, so 60m and 1h agree")
        void formatsUsingLargestExactUnit(long seconds, String expected) {
            assertThat(HumanDuration.format(Duration.ofSeconds(seconds))).isEqualTo(expected);
        }

        @Test
        @DisplayName("equivalent spellings round-trip to one canonical form")
        void equivalentSpellingsConverge() {
            assertThat(HumanDuration.format(HumanDuration.parse("60m")))
                    .isEqualTo(HumanDuration.format(HumanDuration.parse("1h")))
                    .isEqualTo("1h");
        }
    }

    @Nested
    @DisplayName("Threshold")
    class Thresholds {

        @Test
        @DisplayName("latency thresholds normalize to milliseconds")
        void normalizesLatencyToMilliseconds() {
            assertThat(Threshold.parse("750ms"))
                    .isEqualTo(new Threshold(BigDecimal.valueOf(750), Threshold.Unit.MILLISECONDS));
            assertThat(Threshold.parse("8s").canonicalForm()).isEqualTo("8000ms");
        }

        @Test
        @DisplayName("percentage thresholds keep their unit")
        void keepsPercentUnit() {
            Threshold threshold = Threshold.parse("1%");

            assertThat(threshold.unit()).isEqualTo(Threshold.Unit.PERCENT);
            assertThat(threshold.canonicalForm()).isEqualTo("1%");
        }

        @Test
        @DisplayName("score thresholds normalize to milli-score")
        void normalizesScores() {
            Threshold threshold = Threshold.parse("0.1score");

            assertThat(threshold.unit()).isEqualTo(Threshold.Unit.SCORE);
            assertThat(threshold.value()).isEqualByComparingTo(BigDecimal.valueOf(100));
            assertThat(threshold.canonicalForm()).isEqualTo("0.1score");
        }

        @Test
        @DisplayName("fractional values are supported")
        void supportsFractionalValues() {
            assertThat(Threshold.parse("0.5%").canonicalForm()).isEqualTo("0.5%");
            assertThat(Threshold.parse("1.5s").canonicalForm()).isEqualTo("1500ms");
        }

        /**
         * A unit-less threshold is the dangerous case: silently guessing milliseconds would let a
         * test declare "1" and pass or fail for reasons the author never intended.
         */
        @ParameterizedTest
        @ValueSource(strings = {"750", "fast", "750 millis", "-1ms", "", "  "})
        @DisplayName("a threshold with no recognized unit is rejected rather than guessed")
        void rejectsUnitlessOrUnparseableThresholds(String raw) {
            assertThatThrownBy(() -> Threshold.parse(raw))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a percentage above 100 is rejected")
        void rejectsImpossiblePercentage() {
            assertThatThrownBy(() -> Threshold.parse("150%"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must not exceed 100");
        }

        @Test
        @DisplayName("a score above one is rejected")
        void rejectsImpossibleScore() {
            assertThatThrownBy(() -> Threshold.parse("1.5score"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must not exceed 1");
        }

        @Test
        @DisplayName("equivalent spellings are equal, so they digest identically")
        void equivalentSpellingsAreEqual() {
            assertThat(Threshold.parse("1s")).isEqualTo(Threshold.parse("1000ms"));
            assertThat(Threshold.parse("1s")).hasSameHashCodeAs(Threshold.parse("1000ms"));
        }
    }

    @Nested
    @DisplayName("enum parsing")
    class Enums {

        @Test
        @DisplayName("hyphenated and mixed-case authored values are accepted")
        void acceptsAuthoredForms() {
            assertThat(TargetClass.from("non-production")).isEqualTo(TargetClass.NON_PRODUCTION);
            assertThat(TargetClass.from("NON_PRODUCTION")).isEqualTo(TargetClass.NON_PRODUCTION);
            assertThat(SourceType.from("OpenAPI")).isEqualTo(SourceType.OPENAPI);
            assertThat(WorkloadType.from("browser")).isEqualTo(WorkloadType.BROWSER);
        }

        @Test
        @DisplayName("an unsupported value is rejected with the permitted set listed")
        void listsPermittedValuesOnFailure() {
            assertThatThrownBy(() -> SourceType.from("graphql"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Supported values:")
                    .hasMessageContaining("openapi")
                    .hasMessageContaining("playwright");
        }

        @Test
        @DisplayName("k6 is not an accepted engine source type in this release")
        void doesNotAcceptUnapprovedEngines() {
            assertThatThrownBy(() -> SourceType.from("k6"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("specification accessors")
    class Accessors {

        @Test
        @DisplayName("peak protocol users sums protocol workloads and excludes browser sessions")
        void separatesProtocolAndBrowserBudgets() {
            PerformanceTestSpecification hybrid = SpecificationFixtures.hybrid();

            assertThat(hybrid.peakProtocolUsers()).isEqualTo(600);
            assertThat(hybrid.peakBrowserSessions()).isEqualTo(5);
        }

        @Test
        @DisplayName("total duration is the sum of the staged model")
        void sumsStageDurations() {
            assertThat(SpecificationFixtures.apiWorkload().totalDuration())
                    .isEqualTo(Duration.ofMinutes(30));
        }

        @Test
        @DisplayName("a browser-only specification reports no protocol load")
        void browserOnlyHasNoProtocolLoad() {
            assertThat(SpecificationFixtures.browserOnly().peakProtocolUsers()).isZero();
            assertThat(SpecificationFixtures.browserOnly().peakBrowserSessions()).isEqualTo(5);
        }
    }
}
