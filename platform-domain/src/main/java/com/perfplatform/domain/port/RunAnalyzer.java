package com.perfplatform.domain.port;

import com.perfplatform.domain.identity.TestRunId;
import com.perfplatform.domain.results.NormalizedRunResult;
import com.perfplatform.domain.results.ObjectiveOutcome;
import com.perfplatform.domain.results.ObjectiveOutcome.Verdict;
import com.perfplatform.domain.results.SampleSet;
import com.perfplatform.domain.spec.PerformanceTestSpecification.ObjectiveSpec;

import java.util.List;
import java.util.Objects;

/**
 * Turns samples into measurements and a verdict.
 *
 * <p>A port because the arithmetic lives in {@code platform-results} and orchestration must not
 * depend on it (CLAUDE.md §9): the reconciler decides <em>when</em> to analyze, and knows nothing
 * about how a percentile is computed or how a threshold is compared.
 *
 * <p>Implementations must be pure. Analysis is re-run from stored artifacts when a result is
 * questioned, and an implementation that read a clock or a configuration value could produce a
 * different verdict the second time — at which point neither answer means anything.
 */
public interface RunAnalyzer {

    /**
     * @param result   the measurements
     * @param outcomes one verdict per objective, in specification order
     */
    record Analysis(NormalizedRunResult result, List<ObjectiveOutcome> outcomes) {

        public Analysis {
            Objects.requireNonNull(result, "result");
            outcomes = outcomes == null ? List.of() : List.copyOf(outcomes);
        }

        /**
         * Whether the run should be reported as successful.
         *
         * <p>A run with no objectives passes: it asked no questions, so none went unanswered.
         * A run with an unmet or unanswerable objective does not.
         */
        public boolean isSuccessful() {
            return outcomes.stream().noneMatch(ObjectiveOutcome::blocksSuccess);
        }

        /**
         * The same analysis with every verdict restated as {@link Verdict#INDETERMINATE}.
         *
         * <p>For a run whose measurements cannot support a verdict at all — today, one executed by a
         * provider that generates no load. The arithmetic is left intact and still stored, because
         * the numbers are genuinely what the artifacts contain and a report that hid them would be
         * harder to debug; what is removed is any claim about whether they were met.
         *
         * <p>{@code INDETERMINATE} rather than {@code BREACH}: nothing failed. The system under test
         * may be perfectly healthy, and nobody looked. It is also not a pass, which is the whole
         * point — a provider that always produced a green verdict would be the most misleading thing
         * this platform could contain.
         *
         * <p>The threshold is preserved on each outcome so a reader can still see what was being
         * asked; the observed value is zeroed, because there is no observation.
         *
         * @param reason short explanation, shown to the consumer in place of the comparison
         */
        public Analysis asIndeterminate(String reason) {
            List<ObjectiveOutcome> restated = outcomes.stream()
                    .map(outcome -> new ObjectiveOutcome(
                            outcome.objectiveId(),
                            outcome.metric(),
                            outcome.scope(),
                            ObjectiveOutcome.Verdict.INDETERMINATE,
                            0,
                            outcome.threshold(),
                            reason))
                    .toList();
            return new Analysis(result, restated);
        }
    }

    /**
     * @param shardsExpected how many shards the plan called for
     * @param shardsReported how many produced usable output. Fewer means the result is incomplete,
     *                       and no objective may be reported as passing
     */
    Analysis analyze(TestRunId runId, String engine, int shardsExpected, int shardsReported,
            SampleSet samples, List<ObjectiveSpec> objectives);

    /**
     * Judges measurements that have already been reduced.
     *
     * <p>For the live view, whose numbers are merged from runner snapshots rather than reduced from
     * samples. It exists so that "this objective is breached" has exactly one definition in the
     * platform: a live warning computed by a looser rule would eventually disagree with the run's own
     * verdict, and the disagreement would destroy the credibility of both.
     *
     * <p>The outcomes are provisional wherever they are used — the measurements behind them come
     * from partial output and bucketed percentiles — and callers are responsible for saying so. They
     * are never stored as a run's verdict.
     */
    List<ObjectiveOutcome> judge(NormalizedRunResult result, List<ObjectiveSpec> objectives);
}
