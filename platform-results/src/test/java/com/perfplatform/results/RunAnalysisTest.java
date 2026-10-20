package com.perfplatform.results;

import com.perfplatform.domain.identity.TestRunId;
import com.perfplatform.domain.port.RunAnalyzer;
import com.perfplatform.domain.port.RunAnalyzer.Analysis;
import com.perfplatform.domain.results.NormalizedRunResult;
import com.perfplatform.domain.results.ObjectiveOutcome.Verdict;
import com.perfplatform.domain.results.OperationMetrics;
import com.perfplatform.domain.results.Sample;
import com.perfplatform.domain.results.SampleSet;
import com.perfplatform.domain.spec.PerformanceTestSpecification.ObjectiveSpec;
import com.perfplatform.domain.spec.Threshold;
import com.perfplatform.results.metrics.RunResultReducer;
import com.perfplatform.results.objectives.ObjectiveEvaluator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Summarizing samples and judging them.
 *
 * <p>The verdict is the platform's whole output, so these cover not only that a breach is detected
 * but that the situations where a verdict <em>cannot</em> be given are reported as such rather than
 * rounded to a pass.
 */
class RunAnalysisTest {

    private static final TestRunId RUN = TestRunId.generate();
    private static final String ENGINE = "jmeter";
    private static final long START = 1_753_876_800_000L;

    private final RunAnalyzer analyzer = new DefaultRunAnalyzer();

    /** Samples with the given latencies, all successful, one per 100ms. */
    private static SampleSet samples(String operation, long... latencies) {
        List<Sample> list = new ArrayList<>();
        for (int i = 0; i < latencies.length; i++) {
            list.add(new Sample(operation, latencies[i], true, START + i * 100L));
        }
        return new SampleSet(list, 0);
    }

    private static ObjectiveSpec responseTime(String scope, int percentile, String threshold) {
        return new ObjectiveSpec("http.response.time", scope, percentile, Threshold.parse(threshold));
    }

    private static ObjectiveSpec errorRate(String scope, String threshold) {
        return new ObjectiveSpec("http.error.rate", scope, null, Threshold.parse(threshold));
    }

    @Test
    void maximumObjectiveCannotSubstituteP99() {
        long[] latencies = new long[100];
        java.util.Arrays.fill(latencies, 10);
        latencies[99] = 10_000;
        var result = RunResultReducer.reduce(RUN, ENGINE, 1, 1, samples("api", latencies));
        var outcome = ObjectiveEvaluator.evaluate(result, List.of(responseTime(null, 100, "200ms"))).get(0);
        assertThat(outcome.observed()).isEqualTo(10_000);
        assertThat(outcome.verdict()).isEqualTo(Verdict.BREACH);
    }

    @Test
    void p75IsComputedFromSamplesRatherThanP90() {
        var result = RunResultReducer.reduce(RUN, ENGINE, 1, 1, samples("api", 10, 20, 30, 400));
        var outcome = ObjectiveEvaluator.evaluate(result, List.of(responseTime(null, 75, "50ms"))).get(0);
        assertThat(result.overall().p75Millis()).isEqualTo(30);
        assertThat(outcome.observed()).isEqualTo(30);
        assertThat(outcome.verdict()).isEqualTo(Verdict.PASS);
    }

    @Test
    void olderInvalidUnitsAndUnsupportedPercentilesRemainIndeterminate() {
        var result = RunResultReducer.reduce(RUN, ENGINE, 1, 1,
                new SampleSet(List.of(new Sample("api", 10, false, START)), 0));
        assertThat(ObjectiveEvaluator.evaluate(result, List.of(errorRate(null, "1s"))).get(0).verdict())
                .isEqualTo(Verdict.INDETERMINATE);
        assertThat(ObjectiveEvaluator.evaluate(result, List.of(responseTime(null, 98, "200ms"))).get(0).verdict())
                .isEqualTo(Verdict.INDETERMINATE);
    }

    @Test
    void olderSummariesCannotInventP75() {
        var old = new OperationMetrics("*", 1, 0, 0, 10, 10, 10, 10, 10, 10, 10, 1_000);
        var result = new NormalizedRunResult(RUN, ENGINE, java.util.Optional.empty(),
                java.util.Optional.empty(), 1, 1, old, java.util.Map.of());
        assertThat(ObjectiveEvaluator.evaluate(result, List.of(responseTime(null, 75, "200ms"))).get(0).verdict())
                .isEqualTo(Verdict.INDETERMINATE);
    }

    @Nested
    @DisplayName("summarizing")
    class Summarizing {

        @Test
        @DisplayName("percentiles, counts and extremes are computed per operation and overall")
        void summarizesSamples() {
            SampleSet set = samples("search", 10, 20, 30, 40, 50, 60, 70, 80, 90, 100);

            NormalizedRunResult result = RunResultReducer.reduce(RUN, ENGINE, 1, 1, set);

            assertThat(result.overall().sampleCount()).isEqualTo(10);
            assertThat(result.overall().minMillis()).isEqualTo(10);
            assertThat(result.overall().maxMillis()).isEqualTo(100);
            assertThat(result.overall().meanMillis()).isEqualTo(55);
            assertThat(result.overall().p95Millis()).isEqualTo(100);
            assertThat(result.operation("search")).isPresent();
        }

        @Test
        @DisplayName("operations are summarized separately, so per-scenario objectives work")
        void summarizesPerOperation() {
            SampleSet set = samples("fast", 10, 10, 10).merge(samples("slow", 900, 900, 900));

            NormalizedRunResult result = RunResultReducer.reduce(RUN, ENGINE, 1, 1, set);

            assertThat(result.operation("fast").orElseThrow().p95Millis()).isEqualTo(10);
            assertThat(result.operation("slow").orElseThrow().p95Millis()).isEqualTo(900);
            assertThat(result.operationNames()).containsExactly("fast", "slow");
        }

        /**
         * Unreadable rows stay in the error-rate denominator. A denominator that excluded them
         * would report a better error rate the more badly the run went (defect D5).
         */
        @Test
        @DisplayName("unreadable rows are kept in the error-rate denominator")
        void unreadableRowsCountAgainstTheErrorRate() {
            List<Sample> ok = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                ok.add(new Sample("search", 10, true, START + i * 100L));
            }
            ok.add(new Sample("search", 10, false, START + 900));

            // 9 readable (1 failed) plus 1 unreadable = 10 attempted, 1 error = 10%.
            SampleSet set = new SampleSet(ok, 1);
            NormalizedRunResult result = RunResultReducer.reduce(RUN, ENGINE, 1, 1, set);

            assertThat(result.overall().unparsedCount()).isEqualTo(1);
            assertThat(result.overall().errorRateMilliPercent()).isEqualTo(10_000);
        }

        @Test
        @DisplayName("summarizing the same samples twice gives the same numbers")
        void summarizingIsDeterministic() {
            SampleSet set = samples("search", 10, 20, 30, 40, 50);

            assertThat(RunResultReducer.reduce(RUN, ENGINE, 1, 1, set))
                    .isEqualTo(RunResultReducer.reduce(RUN, ENGINE, 1, 1, set));
        }

        @Test
        @DisplayName("a run that measured nothing still reports its shard counts")
        void emptyResultKeepsShardCounts() {
            NormalizedRunResult result = RunResultReducer.reduce(RUN, ENGINE, 4, 0, SampleSet.empty());

            assertThat(result.overall().sampleCount()).isZero();
            assertThat(result.shardsExpected()).isEqualTo(4);
            assertThat(result.shardsReported()).isZero();
            assertThat(result.isComplete()).isFalse();
        }
    }

    @Nested
    @DisplayName("judging")
    class Judging {

        @Test
        @DisplayName("a latency inside the ceiling passes")
        void latencyPasses() {
            Analysis analysis = analyzer.analyze(RUN, ENGINE, 1, 1,
                    samples("search", 100, 200, 300),
                    List.of(responseTime(OperationMetrics.ALL, 95, "750ms")));

            assertThat(analysis.outcomes()).singleElement()
                    .satisfies(outcome -> assertThat(outcome.verdict()).isEqualTo(Verdict.PASS));
            assertThat(analysis.isSuccessful()).isTrue();
        }

        @Test
        @DisplayName("a latency over the ceiling breaches, and says what it measured")
        void latencyBreaches() {
            Analysis analysis = analyzer.analyze(RUN, ENGINE, 1, 1,
                    samples("search", 800, 900, 1000),
                    List.of(responseTime(OperationMetrics.ALL, 95, "750ms")));

            assertThat(analysis.outcomes()).singleElement().satisfies(outcome -> {
                assertThat(outcome.verdict()).isEqualTo(Verdict.BREACH);
                assertThat(outcome.threshold()).isEqualTo(750);
                assertThat(outcome.detail()).contains("p95").contains("750ms");
            });
            assertThat(analysis.isSuccessful()).isFalse();
        }

        @Test
        @DisplayName("an error rate is compared in exact scaled integers")
        void errorRateIsExact() {
            List<Sample> mixed = new ArrayList<>();
            for (int i = 0; i < 99; i++) {
                mixed.add(new Sample("search", 10, true, START + i * 10L));
            }
            mixed.add(new Sample("search", 10, false, START + 990));

            // Exactly 1%, against a 1% ceiling: met, not breached.
            Analysis analysis = analyzer.analyze(RUN, ENGINE, 1, 1,
                    new SampleSet(mixed, 0), List.of(errorRate(OperationMetrics.ALL, "1%")));

            assertThat(analysis.outcomes()).singleElement().satisfies(outcome -> {
                assertThat(outcome.verdict()).isEqualTo(Verdict.PASS);
                assertThat(outcome.observed()).isEqualTo(1_000);
                assertThat(outcome.threshold()).isEqualTo(1_000);
            });
        }

        @Test
        @DisplayName("an objective scoped to one operation judges only that operation")
        void scopedObjective() {
            SampleSet set = samples("fast", 10, 10, 10).merge(samples("slow", 900, 900, 900));

            Analysis analysis = analyzer.analyze(RUN, ENGINE, 1, 1, set, List.of(
                    responseTime("fast", 95, "100ms"),
                    responseTime("slow", 95, "100ms")));

            assertThat(analysis.outcomes()).extracting(outcome -> outcome.verdict())
                    .containsExactly(Verdict.PASS, Verdict.BREACH);
        }

        @Test
        @DisplayName("web-vital objectives resolve metric-prefixed browser samples")
        void webVitalObjectivesResolveMetricScopes() {
            SampleSet set = samples("web.lcp@browser-experience/home", 1800, 2200)
                    .merge(samples("web.cls@browser-experience/home", 50, 95))
                    .merge(samples("web.lighthouse.score@browser-experience/home", 92, 94));

            Analysis analysis = analyzer.analyze(RUN, "playwright", 1, 1, set, List.of(
                    new ObjectiveSpec("web.lcp", "browser-experience", 95,
                            Threshold.parse("2500ms")),
                    new ObjectiveSpec("web.cls", "browser-experience", 95,
                            Threshold.parse("0.1score")),
                    new ObjectiveSpec("web.lighthouse.score", "browser-experience", null,
                            Threshold.parse("90%"))));

            assertThat(analysis.outcomes()).extracting(outcome -> outcome.verdict())
                    .containsExactly(Verdict.PASS, Verdict.PASS, Verdict.PASS);
            assertThat(analysis.outcomes().get(1).detail()).contains("0.1score");
        }

        @Test
        @DisplayName("a run with no objectives is successful — it asked no questions")
        void noObjectivesIsSuccessful() {
            Analysis analysis = analyzer.analyze(RUN, ENGINE, 1, 1,
                    samples("search", 10), List.of());

            assertThat(analysis.outcomes()).isEmpty();
            assertThat(analysis.isSuccessful()).isTrue();
        }
    }

    @Nested
    @DisplayName("what cannot be judged")
    class Indeterminate {

        /**
         * The most important rule here. A run whose shards did not all report measured less load
         * than its plan describes; reporting the surviving shard's latencies as the test's outcome
         * would state a pass the test never demonstrated.
         */
        @Test
        @DisplayName("an incomplete run cannot pass, however good its numbers look")
        void incompleteRunCannotPass() {
            Analysis analysis = analyzer.analyze(RUN, ENGINE, 4, 2,
                    samples("search", 1, 1, 1),
                    List.of(responseTime(OperationMetrics.ALL, 95, "750ms")));

            assertThat(analysis.outcomes()).singleElement().satisfies(outcome -> {
                assertThat(outcome.verdict()).isEqualTo(Verdict.INDETERMINATE);
                assertThat(outcome.detail()).contains("2 of 4");
            });
            assertThat(analysis.isSuccessful()).isFalse();
        }

        @Test
        @DisplayName("an objective over a scope with no samples cannot pass")
        void missingScopeCannotPass() {
            Analysis analysis = analyzer.analyze(RUN, ENGINE, 1, 1,
                    samples("search", 10, 10),
                    List.of(responseTime("checkout", 95, "750ms")));

            assertThat(analysis.outcomes()).singleElement().satisfies(outcome -> {
                assertThat(outcome.verdict()).isEqualTo(Verdict.INDETERMINATE);
                assertThat(outcome.detail()).contains("checkout");
            });
        }

        @Test
        @DisplayName("a run that measured nothing cannot pass")
        void emptyRunCannotPass() {
            Analysis analysis = analyzer.analyze(RUN, ENGINE, 1, 1, SampleSet.empty(),
                    List.of(responseTime(OperationMetrics.ALL, 95, "750ms")));

            assertThat(analysis.outcomes()).singleElement()
                    .satisfies(outcome ->
                            assertThat(outcome.verdict()).isEqualTo(Verdict.INDETERMINATE));
        }

        /**
         * An unrecognized metric is neither passed nor failed. Passing it would claim a check
         * happened; failing it would fail a run for asking a question this release cannot answer.
         */
        @Test
        @DisplayName("an unrecognized metric is reported, not assumed")
        void unknownMetricIsReported() {
            Analysis analysis = analyzer.analyze(RUN, ENGINE, 1, 1, samples("search", 10),
                    List.of(new ObjectiveSpec("custom.browser.metric", null, null,
                            Threshold.parse("2s"))));

            assertThat(analysis.outcomes()).singleElement().satisfies(outcome -> {
                assertThat(outcome.verdict()).isEqualTo(Verdict.INDETERMINATE);
                assertThat(outcome.detail()).contains("not evaluated by this release");
            });
        }
    }

    @Test
    @DisplayName("an objective keeps its identifier when another is added above it")
    void objectiveIdentifiersAreStable() {
        ObjectiveSpec latency = responseTime("search", 95, "750ms");

        String before = ObjectiveEvaluator
                .evaluate(RunResultReducer.reduce(RUN, ENGINE, 1, 1, samples("search", 10)),
                        List.of(latency))
                .get(0).objectiveId();

        String after = ObjectiveEvaluator
                .evaluate(RunResultReducer.reduce(RUN, ENGINE, 1, 1, samples("search", 10)),
                        List.of(errorRate("search", "1%"), latency))
                .get(1).objectiveId();

        // Derived from metric, scope and percentile — not from position. Comparing two runs
        // otherwise lines up different objectives and reports a regression that did not happen.
        assertThat(after).isEqualTo(before);
    }
}
