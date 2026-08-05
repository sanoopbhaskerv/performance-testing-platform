package com.perfplatform.persistence.recorder;

import com.perfplatform.domain.identity.RecorderSessionId;
import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.recorder.RecorderAuditEvent;
import com.perfplatform.domain.run.RunEventDetail;

/** Converts recorder audit events between domain and persistence. */
final class RecorderAuditEventMapper {

    private RecorderAuditEventMapper() {
    }

    static RecorderAuditEventEntity toEntity(RecorderAuditEvent event) {
        return new RecorderAuditEventEntity(
                event.sessionId().value(),
                event.teamId().value(),
                event.type(),
                event.actor(),
                event.detailJson(),
                event.occurredAt());
    }

    static RecorderAuditEvent toDomain(RecorderAuditEventEntity entity) {
        return new RecorderAuditEvent(
                entity.getSequence() == null ? 0L : entity.getSequence(),
                RecorderSessionId.of(entity.getSessionId()),
                TeamId.of(entity.getTeamId()),
                entity.getEventType(),
                entity.getActor(),
                RunEventDetail.fromJson(entity.getDetail()),
                entity.getOccurredAt());
    }
}
