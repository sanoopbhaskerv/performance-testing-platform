package com.perfplatform.results.objectives;

import com.perfplatform.domain.results.NormalizedRunResult;
import com.perfplatform.domain.results.ObjectiveOutcome;
import com.perfplatform.domain.results.ObjectiveOutcome.Verdict;
import com.perfplatform.domain.results.OperationMetrics;
import com.perfplatform.domain.spec.PerformanceTestSpecification.ObjectiveSpec;
import com.perfplatform.domain.spec.Threshold;

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

        // Incompleteness is checked before the numbers, because no measurement is trustworthy when
        // part of the load never ran.
        if (!result.isComplete()) {
            return indeterminate(objectiveId, objective.metric(), scope, thresholdValue,
                    "Only " + result.shardsReported() + " of " + result.shardsExpected()
                            + " runner shards reported. The run measured less load than its plan "
                            + "describes, so this objective cannot be judged.");
        }

        Optional<OperationMetrics> metrics = scopeMetrics(result, scope);
        if (metrics.isEmpty() || !metrics.get().hasSamples()) {
            return indeterminate(objectiveId, objective.metric(), scope, thresholdValue,
                    "No samples were recorded for '" + scope + "', so there is nothing to judge.");
        }

        return switch (objective.metric()) {
            case RESPONSE_TIME -> evaluateResponseTime(
                    objectiveId, objective, scope, metrics.get(), thresholdValue);
            case ERROR_RATE -> evaluateErrorRate(
                    objectiveId, objective, scope, metrics.get(), threshold);
            case THROUGHPUT -> evaluateThroughput(
                    objectiveId, objective, scope, metrics.get(), thresholdValue);
            default -> indeterminate(objectiveId, objective.metric(), scope, thresholdValue,
                    "Metric '" + objective.metric() + "' is not evaluated by this release. "
                            + "It was neither passed nor failed.");
        };
    }

    /** A latency ceiling: the measured percentile must not exceed the threshold. */
    private static ObjectiveOutcome evaluateResponseTime(String objectiveId, ObjectiveSpec objective,
            String scope, OperationMetrics metrics, long thresholdMillis) {

        int percentile = objective.percentile() == null ? 95 : objective.percentile();
        long observed = percentileOf(metrics, percentile);

        boolean met = observed <= thresholdMillis;
        return new ObjectiveOutcome(objectiveId, objective.metric(), scope,
                met ? Verdict.PASS : Verdict.BREACH,
                observed, thresholdMillis,
                "p" + percentile + " was " + observed + "ms against a ceiling of "
                        + thresholdMillis + "ms.");
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

    private static Optional<OperationMetrics> scopeMetrics(NormalizedRunResult result, String scope) {
        if (OperationMetrics.ALL.equals(scope)) {
            return Optional.of(result.overall());
        }
        // A scope naming a workload rather than an operation falls back to the aggregate: an
        // objective written against "api-background-load" means the whole of that workload.
        return result.operation(scope).or(() -> result.byOperation().isEmpty()
                ? Optional.of(result.overall())
                : Optional.empty());
    }

    /**
     * The requested percentile.
     *
     * <p>Only the percentiles the normalized model carries are answerable. An objective asking for
     * p99.9 gets p99 rather than a fabricated number, and says so — inventing a percentile from
     * summary statistics would produce a verdict the samples do not support.
     */
    private static long percentileOf(OperationMetrics metrics, int percentile) {
        if (percentile <= 50) {
            return metrics.p50Millis();
        }
        if (percentile <= 90) {
            return metrics.p90Millis();
        }
        if (percentile <= 95) {
            return metrics.p95Millis();
        }
        return metrics.p99Millis();
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
}
