package com.perfplatform.domain.recorder;

import com.perfplatform.domain.identity.RecorderSessionId;
import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.run.RunEventDetail;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** One append-only audit event for an interactive recorder session. */
public record RecorderAuditEvent(
        Long sequence,
        RecorderSessionId sessionId,
        TeamId teamId,
        RecorderAuditEventType type,
        String actor,
        Map<String, String> detail,
        Instant occurredAt) {

    public static final String SYSTEM_ACTOR = "system";

    public RecorderAuditEvent {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(type, "type");
        actor = actor == null || actor.isBlank() ? SYSTEM_ACTOR : actor.trim();
        detail = sanitize(detail);
        Objects.requireNonNull(occurredAt, "occurredAt");
    }

    public static RecorderAuditEvent of(RecorderSessionId sessionId, TeamId teamId,
            RecorderAuditEventType type, String actor, Map<String, String> detail, Instant at) {
        return new RecorderAuditEvent(null, sessionId, teamId, type, actor, detail, at);
    }

    public String detailJson() {
        return RunEventDetail.toJson(detail);
    }

    private static Map<String, String> sanitize(Map<String, String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, String> sanitized = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            String safeKey = sanitizeToken(key, 80);
            if (safeKey.isBlank()) {
                return;
            }
            sanitized.put(safeKey, sanitizeToken(value, 300));
        });
        return Map.copyOf(sanitized);
    }

    private static String sanitizeToken(String value, int max) {
        if (value == null) {
            return "";
        }
        String text = value
                .replace('\n', ' ')
                .replace('\r', ' ')
                .replace('\t', ' ')
                .trim();
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, Math.max(0, max - 3)) + "...";
    }
}
