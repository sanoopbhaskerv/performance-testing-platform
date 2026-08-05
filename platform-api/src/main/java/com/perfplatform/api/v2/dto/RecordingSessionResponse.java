package com.perfplatform.api.v2.dto;

import com.perfplatform.orchestration.recorder.ManageRecordingsUseCase.RecordingSession;

import java.util.List;

/**
 * A governed recorder session.
 *
 * @param bridge        what this deployment runs the browser with, so a session that stays in
 *                      {@code PENDING_BROWSER_BRIDGE} explains itself instead of appearing stuck
 * @param viewportWidth the surface a client must scale its pointer coordinates to
 */
public record RecordingSessionResponse(
        String id,
        String teamId,
        String application,
        String environment,
        String target,
        String status,
        String browserUrl,
        int viewportWidth,
        int viewportHeight,
        String bridge,
        String failureCode,
        String failureReason,
        long capturedCount,
        long blockedCount,
        List<RecorderAuditEventResponse> events) {

    public static RecordingSessionResponse from(RecordingSession session) {
        return new RecordingSessionResponse(
                session.id().toString(),
                session.teamId().toString(),
                session.application(),
                session.environment(),
                session.target(),
                session.status().name(),
                session.browserUrl(),
                session.viewport().width(),
                session.viewport().height(),
                session.bridge(),
                session.failureCode(),
                session.failureReason(),
                session.capturedCount(),
                session.blockedCount(),
                session.events().stream()
                        .map(RecorderAuditEventResponse::from)
                        .toList());
    }
}
