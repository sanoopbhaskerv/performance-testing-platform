package com.perfplatform.api.v2.dto;

import com.perfplatform.orchestration.recorder.ManageRecordingsUseCase.RecordingSession;

import java.util.List;

/** A governed recorder session. */
public record RecordingSessionResponse(
        String id,
        String teamId,
        String application,
        String environment,
        String target,
        String status,
        String browserUrl,
        List<RecorderAuditEventResponse> events) {

    public static RecordingSessionResponse from(RecordingSession session) {
        return new RecordingSessionResponse(
                session.id().toString(),
                session.teamId().toString(),
                session.application(),
                session.environment(),
                session.target(),
                session.status(),
                session.browserUrl(),
                session.events().stream()
                        .map(RecorderAuditEventResponse::from)
                        .toList());
    }
}
