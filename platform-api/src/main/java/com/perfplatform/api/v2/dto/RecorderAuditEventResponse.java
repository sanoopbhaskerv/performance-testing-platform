package com.perfplatform.api.v2.dto;

import com.perfplatform.domain.recorder.RecorderAuditEvent;

import java.time.Instant;
import java.util.Map;

/** Public shape of one recorder audit event. */
public record RecorderAuditEventResponse(
        long sequence,
        String type,
        String actor,
        Map<String, String> detail,
        Instant occurredAt) {

    public static RecorderAuditEventResponse from(RecorderAuditEvent event) {
        return new RecorderAuditEventResponse(
                event.sequence() == null ? 0L : event.sequence(),
                event.type().name(),
                event.actor(),
                event.detail(),
                event.occurredAt());
    }
}
