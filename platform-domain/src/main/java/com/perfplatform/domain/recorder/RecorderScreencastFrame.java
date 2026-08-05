package com.perfplatform.domain.recorder;

import java.time.Instant;
import java.util.Objects;

/**
 * One JPEG frame of the recorded browser surface.
 *
 * <p>Frames carry a monotonic {@code sequence} so a client that falls behind, reconnects or loses
 * a poll can ask for what it has not seen instead of restarting the stream. They are never durable
 * artifacts: a screenshot of a page a person just signed into is credential-adjacent evidence
 * (recorder security review §5), so frames live in memory for the session and are dropped with it.
 *
 * @param sequence   monotonic within one session, starting at 1
 * @param imageBase64 base64-encoded JPEG, no data-URI prefix
 * @param viewport   the surface the frame depicts, for pointer coordinate agreement
 * @param capturedAt when the browser produced the frame
 */
public record RecorderScreencastFrame(
        long sequence,
        String imageBase64,
        RecorderViewport viewport,
        Instant capturedAt) {

    public RecorderScreencastFrame {
        if (sequence < 1) {
            throw new IllegalArgumentException("Frame sequence must start at 1");
        }
        if (imageBase64 == null || imageBase64.isBlank()) {
            throw new IllegalArgumentException("Frame image must not be blank");
        }
        Objects.requireNonNull(viewport, "viewport");
        Objects.requireNonNull(capturedAt, "capturedAt");
    }

    public int sizeBytes() {
        return imageBase64.length();
    }
}
