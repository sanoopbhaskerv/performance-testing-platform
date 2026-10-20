package com.perfplatform.api.v2.dto;

import com.perfplatform.orchestration.recorder.LiveRecorderSession;
import com.perfplatform.orchestration.recorder.ManageRecordingsUseCase.LiveUpdate;

import java.time.Instant;
import java.util.List;

/**
 * One poll of a live recording session.
 *
 * <p>Every list is keyed by a sequence the client echoes back on its next request, so a slow or
 * reconnecting client resumes rather than restarting. {@code frame} is the newest frame only:
 * replaying skipped frames would show the user the past at the moment they are trying to interact
 * with the present.
 */
public record RecorderLiveUpdateResponse(
        String status,
        int viewportWidth,
        int viewportHeight,
        FrameResponse frame,
        List<BlockedDestinationResponse> blocked,
        List<CapturedExchangeResponse> captures,
        String failureCode,
        String failureReason) {

    /** @param imageBase64 a JPEG, without a data-URI prefix */
    public record FrameResponse(long sequence, String imageBase64, int width, int height,
            Instant capturedAt) {
    }

    /** @param destination reduced to origin and path; query strings can carry grant codes */
    public record BlockedDestinationResponse(long sequence, String destination, String kind,
            String code, String reason, Instant blockedAt) {
    }

    public record CapturedExchangeResponse(long sequence, String method, String url,
            String requestContentType, String responseContentType, int findingCount,
            Instant capturedAt) {
    }

    public static RecorderLiveUpdateResponse from(LiveUpdate update) {
        return new RecorderLiveUpdateResponse(
                update.status().name(),
                update.viewport().width(),
                update.viewport().height(),
                update.frame()
                        .map(frame -> new FrameResponse(frame.sequence(), frame.imageBase64(),
                                frame.viewport().width(), frame.viewport().height(),
                                frame.capturedAt()))
                        .orElse(null),
                update.blocked().stream()
                        .map(blocked -> new BlockedDestinationResponse(blocked.sequence(),
                                withoutQuery(blocked.destination()), blocked.kind().name(),
                                blocked.code(), blocked.reason(), blocked.blockedAt()))
                        .toList(),
                update.captures().stream()
                        .map(RecorderLiveUpdateResponse::capture)
                        .toList(),
                update.failureCode(),
                update.failureReason());
    }

    private static CapturedExchangeResponse capture(LiveRecorderSession.CapturedExchange exchange) {
        return new CapturedExchangeResponse(
                exchange.sequence(),
                exchange.capture().method(),
                withoutQuery(exchange.capture().url()),
                exchange.capture().requestContentType(),
                exchange.capture().responseContentType(),
                exchange.capture().findings().size(),
                exchange.capturedAt());
    }

    /**
     * Drops the query string.
     *
     * <p>The sanitizer already removes credential headers and unsafe bodies, but a URL is neither.
     * A recording session is where someone signs in, and an OAuth callback carries its grant in the
     * query — so the live view shows where a request went, not what it carried.
     */
    private static String withoutQuery(String url) {
        if (url == null) {
            return null;
        }
        int query = url.indexOf('?');
        return query < 0 ? url : url.substring(0, query);
    }
}
