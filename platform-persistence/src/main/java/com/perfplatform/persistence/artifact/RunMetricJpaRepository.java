package com.perfplatform.persistence.artifact;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;

import java.util.List;
import java.util.UUID;

/** Spring Data access to a run's measurement rows. */
public interface RunMetricJpaRepository extends JpaRepository<RunMetricEntity, UUID> {

    /**
     * Ordered by operation so a report lists them the same way every time it is opened.
     *
     * <p>The aggregate row is named {@code *}, which sorts before any operation name, so it comes
     * first — which is also how a report should read: the whole test, then the parts.
     */
    List<RunMetricEntity> findByRunIdOrderByOperationAsc(UUID runId);

    /**
     * Clears a run's measurements so a re-analysis replaces rather than appends.
     *
     * <p>Deletes a <em>derived</em> summary, never evidence. The results file it was reduced from is
     * untouched, which is what makes these numbers recomputable rather than merely stored.
     */
    @Modifying
    void deleteByRunId(UUID runId);
}
