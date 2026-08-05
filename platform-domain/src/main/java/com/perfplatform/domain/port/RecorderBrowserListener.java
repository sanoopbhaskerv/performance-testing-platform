package com.perfplatform.domain.port;

import com.perfplatform.domain.recorder.RecorderCapture;
import com.perfplatform.domain.recorder.RecorderDecision;
import com.perfplatform.domain.recorder.RecorderRequestKind;
import com.perfplatform.domain.recorder.RecorderViewport;

import java.time.Instant;

/**
 * What the control plane wants to hear from a recorder browser.
 *
 * <p>Callbacks arrive on the adapter's own threads and must not block: a slow listener stalls the
 * screencast and, worse, stalls request interception, which would leave the browser waiting on a
 * routing decision. Implementations buffer and return.
 *
 * <p>Note what is <em>not</em> here. There is no callback carrying a DevTools event, a console
 * message or an evaluation result. The listener sees the recording, not the browser.
 */
public interface RecorderBrowserListener {

    /** A new frame of the recorded surface. */
    void onFrame(String imageBase64, RecorderViewport viewport, Instant capturedAt);

    /** The route guard refused a destination the page or user attempted. */
    void onBlocked(String destination, RecorderRequestKind kind, RecorderDecision decision,
            Instant blockedAt);

    /** One completed request/response exchange, still raw and not yet safe to store. */
    void onCapture(RecorderCapture capture, Instant capturedAt);

    /**
     * The browser ended, for any reason including its own crash.
     *
     * @param code a stable {@code recorder.browser.*} code
     */
    void onClosed(String code, String reason, Instant closedAt);
}
