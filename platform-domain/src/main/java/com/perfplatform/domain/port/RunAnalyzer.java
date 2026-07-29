package com.perfplatform.domain.port;

import com.perfplatform.domain.identity.TestRunId;
import com.perfplatform.domain.results.NormalizedRunResult;
import com.perfplatform.domain.results.ObjectiveOutcome;
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
    }

    /**
     * @param shardsExpected how many shards the plan called for
     * @param shardsReported how many produced usable output. Fewer means the result is incomplete,
     *                       and no objective may be reported as passing
     */
    Analysis analyze(TestRunId runId, String engine, int shardsExpected, int shardsReported,
            SampleSet samples, List<ObjectiveSpec> objectives);
}
