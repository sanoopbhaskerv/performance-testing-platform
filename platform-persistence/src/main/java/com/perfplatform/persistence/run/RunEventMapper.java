package com.perfplatform.persistence.run;

import com.perfplatform.domain.identity.TestRunId;
import com.perfplatform.domain.run.RunEvent;
import com.perfplatform.domain.run.RunEventDetail;

import java.util.Optional;

/** Converts between the event row and the event record. */
final class RunEventMapper {

    private RunEventMapper() {
    }

    static RunEventEntity toEntity(RunEvent event) {
        return new RunEventEntity(
                event.runId().value(),
                event.type(),
                event.previousState().orElse(null),
                event.newState().orElse(null),
                event.actor(),
                // Rendered by the domain, so this module acquires no JSON binding and the stored
                // text is deterministic — keys sorted, so the same detail always looks the same.
                RunEventDetail.toJson(event.detail()),
                event.occurredAt());
    }

    static RunEvent toDomain(RunEventEntity entity) {
        return new RunEvent(
                entity.getSequence() == null ? 0L : entity.getSequence(),
                TestRunId.of(entity.getRunId()),
                entity.getEventType(),
                Optional.ofNullable(entity.getPreviousState()),
                Optional.ofNullable(entity.getNewState()),
                entity.getActor(),
                RunEventDetail.fromJson(entity.getDetail()),
                entity.getOccurredAt());
    }
}
