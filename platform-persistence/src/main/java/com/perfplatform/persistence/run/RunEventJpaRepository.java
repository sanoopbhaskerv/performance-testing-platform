package com.perfplatform.persistence.run;

import com.perfplatform.domain.run.RunEventType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data access to run event rows.
 *
 * <p>Insert and read only. No update or delete method exists here, which is the application-level
 * half of the append-only guarantee; the PostgreSQL trigger is the half that survives a direct SQL
 * write.
 */
public interface RunEventJpaRepository extends JpaRepository<RunEventEntity, Long> {

    /**
     * A run's history after a given sequence.
     *
     * <p>The bound lets a client that dropped a connection resume from where it stopped rather than
     * re-reading a long history — a run lasting hours can accumulate a great many events.
     */
    List<RunEventEntity> findByRunIdAndSequenceGreaterThanOrderBySequenceAsc(
            UUID runId, long afterSequence, Limit limit);

    /**
     * Whether a run already has an event of this type.
     *
     * <p>Used to keep a repeatedly deferred run from accumulating an identical event on every
     * reconciliation pass, which would bury the events that describe what actually happened.
     */
    boolean existsByRunIdAndEventType(UUID runId, RunEventType eventType);
}
