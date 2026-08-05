package com.perfplatform.domain.recorder;

/**
 * Browser request surfaces the recorder must gate.
 *
 * <p>The policy is intentionally applied to more than top-level navigation. A user can stay on an
 * approved page while that page opens a popup, creates an iframe, starts a worker, calls
 * {@code fetch()} or opens a WebSocket to somewhere else. Every one of those is network reach from
 * inside the runner cell and must take the same allowlist decision.
 */
public enum RecorderRequestKind {
    TOP_LEVEL_NAVIGATION,
    SUBRESOURCE,
    POPUP,
    IFRAME,
    WORKER,
    FETCH,
    WEBSOCKET,
    EVENT_SOURCE
}
