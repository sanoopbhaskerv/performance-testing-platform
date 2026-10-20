package com.perfplatform.results.objectives;

import com.perfplatform.domain.results.NormalizedRunResult;
import com.perfplatform.domain.results.ObjectiveOutcome;
import com.perfplatform.domain.results.ObjectiveOutcome.Verdict;
import com.perfplatform.domain.results.OperationMetrics;
import com.perfplatform.domain.spec.PerformanceTestSpecification.ObjectiveSpec;
import com.perfplatform.domain.spec.Threshold;
import com.perfplatform.domain.validation.ObjectiveContract;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Decides whether a run met its objectives.
 *
 * <p>A pure function of a {@link NormalizedRunResult} and the specification's objectives. It reads
 * no clock, no database and no configuration, which is what makes a verdict re-derivable: given the
 * stored artifacts, the same answer comes out months later, and a disputed pass or fail can be
 * settled by recomputation rather than by argument.
 *
 * <p>This is where {@code SlaEvaluator}'s judgement moves to. What it did — compare a p95 against a
 * ceiling, compare an error rate against a ceiling — is preserved. What changes is that it now
 * works on normalized measurements rather than parsing JTL rows itself, so the same objectives
 * apply to any engine, and that it can no longer report a pass over incomplete data.
 *
 * <h2>Incomplete data never passes</h2>
 * Two cases produce {@link Verdict#INDETERMINATE} rather than a pass:
 * <ul>
 *   <li>a run whose shards did not all report — it measured less load than the plan describes;</li>
 *   <li>an objective whose scope produced no samples at all — nothing was measured to judge.</li>
 * </ul>
 * Neither is a failure of the system under test, and calling either a pass is how a platform tells
 * someone their service is fine when nobody looked.
 */
public final class ObjectiveEvaluator {

    /** Metrics this release understands. An unrecognized metric is reported, never assumed. */
    public static final String RESPONSE_TIME = "http.response.time";
    public static final String ERROR_RATE = "http.error.rate";
    public static final String THROUGHPUT = "http.throughput";
    public static final String BROWSER_JOURNEY_DURATION = "browser.journey.duration";
    public static final String BROWSER_STEP_DURATION = "browser.step.duration";
    public static final String BROWSER_ERROR_RATE = "browser.error.rate";
    public static final String WEB_LCP = "web.lcp";
    public static final String WEB_INP = "web.inp";
    public static final String WEB_CLS = "web.cls";
    public static final String WEB_TTFB = "web.ttfb";
    public static final String WEB_TBT = "web.tbt";
    public static final String WEB_LIGHTHOUSE_SCORE = "web.lighthouse.score";

    private ObjectiveEvaluator() {
    }

    /**
     * Evaluates every objective.
     *
     * @param result     the run's normalized measurements
     * @param objectives the objectives from the specification the run executed
     * @return one outcome per objective, in specification order
     */
    public static List<ObjectiveOutcome> evaluate(NormalizedRunResult result,
            List<ObjectiveSpec> objectives) {

        if (objectives == null || objectives.isEmpty()) {
            return List.of();
        }

        List<ObjectiveOutcome> outcomes = new ArrayList<>(objectives.size());
        for (ObjectiveSpec objective : objectives) {
            outcomes.add(evaluateOne(result, objective));
        }
        return List.copyOf(outcomes);
    }

    private static ObjectiveOutcome evaluateOne(NormalizedRunResult result, ObjectiveSpec objective) {
        String scope = objective.scope() == null || objective.scope().isBlank()
                ? OperationMetrics.ALL : objective.scope();
        String objectiveId = objectiveId(objective, scope);
        Threshold threshold = objective.threshold();

        if (threshold == null) {
            return indeterminate(objectiveId, objective.metric(), scope, 0,
                    "The objective declares no threshold, so nothing could be compared.");
        }

        long thresholdValue = thresholdAsLong(threshold);
        // Stored pre-upgrade specifications must not bypass the authoring contract at analysis.
        var contract = ObjectiveContract.validate(objective, "/spec/objectives");
        if (!contract.isValid()) {
            return indeterminate(objectiveId, objective.metric(), scope, thresholdValue,
                    contract.errors().get(0).message());
        }

        // Incompleteness is checked before the numbers, because no measurement is trustworthy when
        // part of the load never ran.
        if (!result.isComplete()) {
            return indeterminate(objectiveId, objective.metric(), scope, thresholdValue,
                    "Only " + result.shardsReported() + " of " + result.shardsExpected()
                            + " runner shards reported. The run measured less load than its plan "
                            + "describes, so this objective cannot be judged.");
        }

        Optional<OperationMetrics> metrics = scopeMetrics(result, objective.metric(), scope);
        if (metrics.isEmpty() || !metrics.get().hasSamples()) {
            return indeterminate(objectiveId, objective.metric(), scope, thresholdValue,
                    "No samples were recorded for '" + scope + "', so there is nothing to judge.");
        }

        return switch (objective.metric()) {
            case RESPONSE_TIME, BROWSER_JOURNEY_DURATION, BROWSER_STEP_DURATION,
                    WEB_LCP, WEB_INP, WEB_TTFB, WEB_TBT -> evaluateCeiling(
                            objectiveId, objective, scope, metrics.get(), thresholdValue, "ms");
            case WEB_CLS -> evaluateCeiling(
                    objectiveId, objective, scope, metrics.get(), thresholdValue, "score");
            case ERROR_RATE, BROWSER_ERROR_RATE -> evaluateErrorRate(
                    objectiveId, objective, scope, metrics.get(), threshold);
            case THROUGHPUT -> evaluateThroughput(
                    objectiveId, objective, scope, metrics.get(), thresholdValue);
            case WEB_LIGHTHOUSE_SCORE -> evaluateScoreFloor(
                    objectiveId, objective, scope, metrics.get(), thresholdValue);
            default -> indeterminate(objectiveId, objective.metric(), scope, thresholdValue,
                    "Metric '" + objective.metric() + "' is not evaluated by this release. "
                            + "It was neither passed nor failed.");
        };
    }

    /** A latency ceiling: the measured percentile must not exceed the threshold. */
    private static ObjectiveOutcome evaluateCeiling(String objectiveId, ObjectiveSpec objective,
            String scope, OperationMetrics metrics, long thresholdValue, String unit) {

        int percentile = objective.percentile() == null ? 95 : objective.percentile();
        if (percentile == 75 && metrics.p75Millis() == null) {
            return indeterminate(objectiveId, objective.metric(), scope, thresholdValue,
                    "This older summary has no p75 measurement. Reanalyze the original evidence.");
        }
        long observed = percentileOf(metrics, percentile);

        boolean met = observed <= thresholdValue;
        return new ObjectiveOutcome(objectiveId, objective.metric(), scope,
                met ? Verdict.PASS : Verdict.BREACH,
                observed, thresholdValue,
                "p" + percentile + " was " + displayValue(observed, unit)
                        + " against a ceiling of " + displayValue(thresholdValue, unit) + ".");
    }

    /**
     * An error-rate ceiling.
     *
     * <p>Compared in scaled integers — hundredths of a percent — rather than as doubles. This
     * comparison is the verdict, and one that could differ in the last bit between two runs of the
     * same analysis would not be deterministic.
     */
    private static ObjectiveOutcome evaluateErrorRate(String objectiveId, ObjectiveSpec objective,
            String scope, OperationMetrics metrics, Threshold threshold) {

        long observedMilliPercent = metrics.errorRateMilliPercent();
        long thresholdMilliPercent = threshold.value()
                .multiply(BigDecimal.valueOf(1000))
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();

        boolean met = observedMilliPercent <= thresholdMilliPercent;
        return new ObjectiveOutcome(objectiveId, objective.metric(), scope,
                met ? Verdict.PASS : Verdict.BREACH,
                observedMilliPercent, thresholdMilliPercent,
                format(observedMilliPercent) + "% of " + (metrics.sampleCount() + metrics.unparsedCount())
                        + " requests failed, against a ceiling of " + format(thresholdMilliPercent) + "%.");
    }

    /** A throughput floor: unlike the others, more is better. */
    private static ObjectiveOutcome evaluateThroughput(String objectiveId, ObjectiveSpec objective,
            String scope, OperationMetrics metrics, long thresholdValue) {

        long observed = metrics.throughputPerSecondMilli() / 1000;
        boolean met = observed >= thresholdValue;
        return new ObjectiveOutcome(objectiveId, objective.metric(), scope,
                met ? Verdict.PASS : Verdict.BREACH,
                observed, thresholdValue,
                "Throughput was " + observed + " requests/second against a floor of "
                        + thresholdValue + ".");
    }

    /** A browser-quality score floor: higher is better. */
    private static ObjectiveOutcome evaluateScoreFloor(String objectiveId, ObjectiveSpec objective,
            String scope, OperationMetrics metrics, long thresholdValue) {

        int percentile = objective.percentile() == null ? 50 : objective.percentile();
        if (percentile == 75 && metrics.p75Millis() == null) {
            return indeterminate(objectiveId, objective.metric(), scope, thresholdValue,
                    "This older summary has no p75 measurement. Reanalyze the original evidence.");
        }
        long observed = percentileOf(metrics, percentile);
        boolean met = observed >= thresholdValue;
        return new ObjectiveOutcome(objectiveId, objective.metric(), scope,
                met ? Verdict.PASS : Verdict.BREACH,
                observed, thresholdValue,
                "p" + percentile + " score was " + observed + "% against a floor of "
                        + thresholdValue + "%.");
    }

    /**
     * The measurements an objective's scope refers to.
     *
     * <p>Three cases, in order. An objective may name the whole test, a single operation, or a
     * workload — and a workload is not an operation, it is a set of them. Operations are reported as
     * {@code workload/scenario}, so a workload scope is resolved by combining every operation under
     * that prefix.
     *
     * <p>A scope that matches nothing returns empty, and the objective is reported as
     * indeterminate. It is not quietly widened to the whole run: a mistyped scope would then be
     * judged against traffic it was never written about, and would pass or fail for the wrong reason.
     */
    private static Optional<OperationMetrics> scopeMetrics(NormalizedRunResult result,
            String metric, String scope) {
        if (isWebVitalMetric(metric)) {
            return metricScopedMetrics(result, metric, scope);
        }

        if (OperationMetrics.ALL.equals(scope)) {
            return Optional.of(result.overall());
        }

        Optional<OperationMetrics> exact = result.operation(scope);
        if (exact.isPresent()) {
            return exact;
        }

        List<OperationMetrics> withinWorkload = result.byOperation().entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(scope + "/"))
                .map(java.util.Map.Entry::getValue)
                .toList();

        return withinWorkload.isEmpty()
                ? Optional.empty()
                : Optional.of(combine(scope, withinWorkload));
    }

    private static Optional<OperationMetrics> metricScopedMetrics(NormalizedRunResult result,
            String metric, String scope) {
        String metricPrefix = metric + "@";
        if (OperationMetrics.ALL.equals(scope)) {
            List<OperationMetrics> allForMetric = result.byOperation().entrySet().stream()
                    .filter(entry -> entry.getKey().startsWith(metricPrefix))
                    .map(java.util.Map.Entry::getValue)
                    .toList();
            return allForMetric.isEmpty()
                    ? Optional.empty()
                    : Optional.of(combine(scope, allForMetric));
        }

        String exactName = metricPrefix + scope;
        Optional<OperationMetrics> exact = result.operation(exactName);
        if (exact.isPresent()) {
            return exact;
        }

        List<OperationMetrics> withinScope = result.byOperation().entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(exactName + "/"))
                .map(java.util.Map.Entry::getValue)
                .toList();

        return withinScope.isEmpty()
                ? Optional.empty()
                : Optional.of(combine(scope, withinScope));
    }

    private static boolean isWebVitalMetric(String metric) {
        return WEB_LCP.equals(metric)
                || WEB_INP.equals(metric)
                || WEB_CLS.equals(metric)
                || WEB_TTFB.equals(metric)
                || WEB_TBT.equals(metric)
                || WEB_LIGHTHOUSE_SCORE.equals(metric);
    }

    /**
     * Combines several operations into one set of measurements for a workload-scoped objective.
     *
     * <p>Percentiles cannot be averaged — the p95 of a workload is not the mean of its operations'
     * p95s — so the highest is taken. That is the conservative direction: a workload passes only if
     * every operation in it would have passed, which is what "this workload responds within 750ms"
     * means to whoever wrote it.
     */
    private static OperationMetrics combine(String scope, List<OperationMetrics> parts) {
        long samples = 0;
        long errors = 0;
        long unparsed = 0;
        long min = Long.MAX_VALUE;
        long max = 0;
        long weightedMean = 0;
        long p50 = 0;
        long p90 = 0;
        long p95 = 0;
        long p99 = 0;
        long throughput = 0;
        Long p75 = 0L;

        for (OperationMetrics part : parts) {
            samples += part.sampleCount();
            errors += part.errorCount();
            unparsed += part.unparsedCount();
            min = Math.min(min, part.minMillis());
            max = Math.max(max, part.maxMillis());
            weightedMean += part.meanMillis() * part.sampleCount();
            p50 = Math.max(p50, part.p50Millis());
            p90 = Math.max(p90, part.p90Millis());
            p95 = Math.max(p95, part.p95Millis());
            p99 = Math.max(p99, part.p99Millis());
            throughput += part.throughputPerSecondMilli();
            p75 = p75 == null || part.p75Millis() == null ? null : Math.max(p75, part.p75Millis());
        }

        return new OperationMetrics(scope, samples, errors, unparsed,
                min == Long.MAX_VALUE ? 0 : min, max,
                samples == 0 ? 0 : weightedMean / samples,
                p50, p90, p95, p99, throughput, p75);
    }

    /**
     * The requested percentile.
     *
     * <p>Only percentiles the normalized model actually carries are answerable. Other authored
     * percentiles are rejected, and older specifications remain indeterminate.
     */
    private static long percentileOf(OperationMetrics metrics, int percentile) {
        return switch (percentile) {
            case 50 -> metrics.p50Millis();
            case 75 -> metrics.p75Millis();
            case 90 -> metrics.p90Millis();
            case 95 -> metrics.p95Millis();
            case 99 -> metrics.p99Millis();
            case 100 -> metrics.maxMillis();
            default -> throw new IllegalArgumentException("Unsupported percentile");
        };
    }

    private static long thresholdAsLong(Threshold threshold) {
        return threshold.value().setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    /**
     * A stable identifier for an objective within a run.
     *
     * <p>Derived from the metric, scope and percentile rather than from position, so the same
     * objective keeps its identifier when another is added above it — otherwise a comparison
     * between two runs would silently line up different objectives.
     */
    private static String objectiveId(ObjectiveSpec objective, String scope) {
        String base = objective.metric() + "@" + scope;
        return objective.percentile() == null ? base : base + "#p" + objective.percentile();
    }

    private static ObjectiveOutcome indeterminate(String objectiveId, String metric, String scope,
            long threshold, String detail) {
        return new ObjectiveOutcome(objectiveId, metric, scope, Verdict.INDETERMINATE,
                0, threshold, detail);
    }

    private static String format(long milliPercent) {
        return BigDecimal.valueOf(milliPercent, 3).stripTrailingZeros().toPlainString();
    }

    private static String displayValue(long value, String unit) {
        if ("score".equals(unit)) {
            return BigDecimal.valueOf(value, 3).stripTrailingZeros().toPlainString() + "score";
        }
        return value + unit;
    }
}
