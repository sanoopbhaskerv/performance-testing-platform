package com.perfplatform.domain.port;

import com.perfplatform.domain.identity.TestRunId;
import com.perfplatform.domain.port.ArtifactStore.ArtifactReference;
import com.perfplatform.domain.results.ObjectiveOutcome;

import java.util.List;

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
     * Records the verdict on each objective, replacing any previous set for this run.
     *
     * <p>Replacement rather than append, because analysis is re-runnable. Re-deriving a verdict from
     * the same artifacts produces the same answer, so replacing is a no-op; if it ever produced a
     * different one, the newer answer is the one derived from what is actually stored.
     */
    void recordObjectiveOutcomes(TestRunId runId, List<ObjectiveOutcome> outcomes);

    /** The stored verdicts for a run, in the order they were evaluated. */
    List<ObjectiveOutcome> objectiveOutcomesFor(TestRunId runId);
}
