package com.perfplatform.persistence.artifact;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;

import java.util.List;
import java.util.UUID;

/** Spring Data access to objective verdict rows. */
public interface ObjectiveResultJpaRepository extends JpaRepository<ObjectiveResultEntity, UUID> {

    /** Ordered by objective identifier so a report reads consistently between runs. */
    List<ObjectiveResultEntity> findByRunIdOrderByObjectiveIdAsc(UUID runId);

    /**
     * Clears a run's verdicts so a re-analysis replaces rather than appends.
     *
     * <p>Deleting evidence would be wrong; this deletes a <em>derived</em> conclusion, which is
     * recomputable from the artifacts at any time. The artifacts themselves are never deleted here.
     */
    @Modifying
    void deleteByRunId(UUID runId);
}
