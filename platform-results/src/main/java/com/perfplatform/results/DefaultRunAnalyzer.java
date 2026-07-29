package com.perfplatform.results;

import com.perfplatform.domain.identity.TestRunId;
import com.perfplatform.domain.port.RunAnalyzer;
import com.perfplatform.domain.results.NormalizedRunResult;
import com.perfplatform.domain.results.ObjectiveOutcome;
import com.perfplatform.domain.results.SampleSet;
import com.perfplatform.domain.spec.PerformanceTestSpecification.ObjectiveSpec;
import com.perfplatform.results.metrics.RunResultReducer;
import com.perfplatform.results.objectives.ObjectiveEvaluator;

import java.util.List;

/**
 * Summarizes samples and judges them against the objectives.
 *
 * <p>The two halves of analysis joined in the order they must happen: reduce, then evaluate. Both
 * are pure, so the whole thing is — which is what lets a disputed verdict be settled by re-running
 * analysis over the stored artifacts rather than by argument.
 */
public final class DefaultRunAnalyzer implements RunAnalyzer {

    @Override
    public Analysis analyze(TestRunId runId, String engine, int shardsExpected, int shardsReported,
            SampleSet samples, List<ObjectiveSpec> objectives) {

        NormalizedRunResult result = RunResultReducer.reduce(
                runId, engine, shardsExpected, shardsReported, samples);

        List<ObjectiveOutcome> outcomes = ObjectiveEvaluator.evaluate(result, objectives);

        return new Analysis(result, outcomes);
    }
}
