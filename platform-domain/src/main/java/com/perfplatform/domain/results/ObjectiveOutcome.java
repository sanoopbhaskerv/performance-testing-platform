package com.perfplatform.domain.results;

import com.perfplatform.domain.spec.support.SpecEnums;

import java.util.Objects;

/**
 * The verdict on one objective.
 *
 * <p>Deterministic and re-derivable: given the same {@link NormalizedRunResult} and the same
 * objective, evaluation always produces this exact record. That is what makes a run's pass or fail
 * defensible months later — the verdict can be recomputed from the stored artifacts rather than
 * taken on trust from whatever the platform happened to record at the time.
 *
 * @param objectiveId stable identifier for the objective within the run, derived from its metric
 *                    and scope so it stays the same across runs of the same definition
 * @param metric      the normalized metric name, e.g. {@code http.response.time}
 * @param scope       the workload or operation it applies to, or {@code *} for the whole test
 * @param verdict     the outcome
 * @param observed    the measured value, in the threshold's own units
 * @param threshold   the value that was required
 * @param detail      short human-readable explanation, safe to show to a consumer
 */
public record ObjectiveOutcome(
        String objectiveId,
        String metric,
        String scope,
        Verdict verdict,
        long observed,
        long threshold,
        String detail) {

    /**
     * Whether an objective was met.
     *
     * <p>{@link #INDETERMINATE} is a first-class outcome, not an error case. An objective evaluated
     * against missing or incomplete data has not passed, and calling that a pass is how a platform
     * tells someone a system is fine when nobody measured it. Calling it a failure would be equally
     * wrong: the system under test may be perfectly healthy.
     */
    public enum Verdict {
        PASS,
        BREACH,
        INDETERMINATE;

        public static Verdict from(String raw) {
            return SpecEnums.parse(Verdict.class, raw, "objective verdict");
        }
    }

    public ObjectiveOutcome {
        Objects.requireNonNull(verdict, "verdict");
        if (objectiveId == null || objectiveId.isBlank()) {
            throw new IllegalArgumentException("objectiveId must not be null or blank");
        }
        if (metric == null || metric.isBlank()) {
            throw new IllegalArgumentException("metric must not be null or blank");
        }
        scope = scope == null || scope.isBlank() ? OperationMetrics.ALL : scope;
        detail = detail == null ? "" : detail;
    }

    public boolean isPass() {
        return verdict == Verdict.PASS;
    }

    /** Whether this outcome should stop the run being reported as successful. */
    public boolean blocksSuccess() {
        return verdict != Verdict.PASS;
    }
}
