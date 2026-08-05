package com.perfplatform.persistence.recorder;

import com.perfplatform.domain.identity.RecorderSessionId;
import com.perfplatform.domain.port.RecorderAuditLog;
import com.perfplatform.domain.recorder.RecorderAuditEvent;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/** PostgreSQL-backed recorder audit log. */
@Repository
public class RecorderAuditLogStore implements RecorderAuditLog {

    private final RecorderAuditEventJpaRepository events;

    public RecorderAuditLogStore(RecorderAuditEventJpaRepository events) {
        this.events = Objects.requireNonNull(events, "events");
    }

    @Override
    @Transactional
    public RecorderAuditEvent append(RecorderAuditEvent event) {
        return RecorderAuditEventMapper.toDomain(events.save(
                RecorderAuditEventMapper.toEntity(event)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<RecorderAuditEvent> events(RecorderSessionId sessionId, long afterSequence,
            int limit) {
        return events.findBySessionIdAndSequenceGreaterThanOrderBySequenceAsc(
                        sessionId.value(), afterSequence, Limit.of(limit))
                .stream()
                .map(RecorderAuditEventMapper::toDomain)
                .toList();
    }
}
