package com.perfplatform.domain.port;

import com.perfplatform.domain.identity.TestRunId;
import com.perfplatform.domain.port.ArtifactStore.ArtifactReference;
import com.perfplatform.domain.results.NormalizedRunResult;
import com.perfplatform.domain.results.ObjectiveOutcome;

import java.util.List;
import java.util.Optional;

/**
 * Durable metadata for what a run produced.
 *
 * <p>Separate from {@link ArtifactStore} on purpose: that stores <em>content</em>, this stores the
 * <em>record</em> of it. They have different lifetimes, different sizes and different failure modes,
 * and in production they are different systems — object storage and PostgreSQL. Keeping them apart
 * means swapping the content store for S3 in Increment 1E touches nothing here.
 *
 * <p>Both operations are idempotent by run. Reconciliation re-runs, and a second analysis pass over
 * the same run must replace its outcomes rather than accumulate a second set — a report showing an
 * objective twice, once passing and once not, is worse than no report.
 */
public interface ResultCatalog {

    /** Records an artifact that has been stored. */
    void recordArtifact(ArtifactReference reference);

    /** Every artifact recorded for a run. */
    List<ArtifactReference> artifactsFor(TestRunId runId);

    /**
     * Records what a run measured and the verdict on each objective, replacing any previous set.
     *
     * <p>One method, therefore one transaction. The measurements and the verdicts derived from them
     * must be written together: a report assembled from a p95 recorded by one analysis and a verdict
     * recorded by another would state a comparison that was never made.
     *
     * <p>Replacement rather than append, because analysis is re-runnable. Re-deriving from the same
     * artifacts produces the same answer, so replacing is a no-op; if it ever produced a different
     * one, the newer answer is the one derived from what is actually stored. Appending instead would
     * eventually show one operation twice with different numbers, which is worse than no report.
     *
     * <p>The measurements are stored rather than recomputed on read for two reasons. Artifacts have a
     * retention policy and results do not — a report that stops working when the JTL is reclaimed is
     * not a report. And re-reducing a large results file on every read puts that cost on the path a
     * person is waiting on. Recomputation from the stored artifacts stays available as the way to
     * settle a disputed number.
     *
     * @param result   the normalized measurements, including the shard counts that say whether they
     *                 are complete
     * @param outcomes one verdict per objective, in specification order
     */
    void recordAnalysis(TestRunId runId, NormalizedRunResult result, List<ObjectiveOutcome> outcomes);

    /** The stored verdicts for a run, in the order they were evaluated. */
    List<ObjectiveOutcome> objectiveOutcomesFor(TestRunId runId);

    /**
     * What a run measured, as recorded by its most recent analysis.
     *
     * <p>Empty when the run has not been analyzed. Deliberately empty rather than a zero-valued
     * result: "nothing has been measured yet" and "every measurement was zero" are different facts,
     * and a report that rendered the first as the second would fabricate a finished test.
     */
    Optional<NormalizedRunResult> runMetricsFor(TestRunId runId);
}
