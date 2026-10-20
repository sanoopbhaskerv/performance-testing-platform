package com.perfplatform.persistence.progress;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;

import java.util.List;
import java.util.UUID;

/** Spring Data access to a run's live snapshots. */
public interface RunProgressJpaRepository extends JpaRepository<RunProgressEntity, UUID> {

    /** The newest point first, so reading only the latest costs one row. */
    List<RunProgressEntity> findByRunIdOrderByCapturedAtDesc(UUID runId, Pageable pageable);

    /** The oldest point, which the trend rules compare the present against. */
    List<RunProgressEntity> findByRunIdOrderByCapturedAtAsc(UUID runId, Pageable pageable);

    /**
     * Whether this exact capture instant is already recorded.
     *
     * <p>The idempotency check. A reconciliation pass that re-reads a runner snapshot it has already
     * merged must not append a second identical point — a flat stretch of duplicates reads as
     * sustained activity on a chart, which is the opposite of what it means.
     */
    boolean existsByRunIdAndCapturedAt(UUID runId, java.time.Instant capturedAt);

    @Modifying
    void deleteByRunId(UUID runId);
}
