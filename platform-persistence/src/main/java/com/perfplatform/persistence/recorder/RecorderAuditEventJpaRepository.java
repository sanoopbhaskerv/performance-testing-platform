package com.perfplatform.persistence.recorder;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Spring Data access to append-only recorder audit rows. */
public interface RecorderAuditEventJpaRepository
        extends JpaRepository<RecorderAuditEventEntity, Long> {

    List<RecorderAuditEventEntity> findBySessionIdAndSequenceGreaterThanOrderBySequenceAsc(
            UUID sessionId, long afterSequence, Limit limit);
}
