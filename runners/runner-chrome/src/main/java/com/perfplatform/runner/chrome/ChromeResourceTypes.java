package com.perfplatform.runner.chrome;

import com.perfplatform.domain.recorder.RecorderRequestKind;

/**
 * Maps DevTools resource types onto the surfaces the route guard reasons about.
 *
 * <p>Chrome reports {@code Document} for both a top-level navigation and an iframe, so the frame
 * identifier decides between them. The distinction matters: the guard blocks {@code file:} and
 * {@code data:} for top-level navigation specifically, and an operator reading the audit trail
 * needs to know whether the user went somewhere or a page pulled something in.
 */
final class ChromeResourceTypes {

    private ChromeResourceTypes() {
    }

    static RecorderRequestKind toRequestKind(String resourceType, String frameId,
            String mainFrameId) {

        String type = resourceType == null ? "" : resourceType;
        return switch (type) {
            case "Document" -> frameId != null && frameId.equals(mainFrameId)
                    ? RecorderRequestKind.TOP_LEVEL_NAVIGATION
                    : RecorderRequestKind.IFRAME;
            case "XHR", "Fetch", "Preflight", "Ping", "Prefetch" -> RecorderRequestKind.FETCH;
            case "EventSource" -> RecorderRequestKind.EVENT_SOURCE;
            case "WebSocket" -> RecorderRequestKind.WEBSOCKET;
            case "Worker", "SharedWorker", "ServiceWorker" -> RecorderRequestKind.WORKER;
            default -> RecorderRequestKind.SUBRESOURCE;
        };
    }

    /**
     * Whether an exchange of this type is worth keeping.
     *
     * <p>A recording exists to become a protocol journey, so images, fonts, stylesheets and scripts
     * are noise — the same static-asset stripping Gatling Studio and k6 Studio both do. Keeping
     * them would also mean holding megabytes of binary response bodies in memory for a session
     * whose whole purpose is a handful of API calls.
     */
    static boolean isWorthCapturing(String resourceType) {
        return switch (resourceType == null ? "" : resourceType) {
            case "Document", "XHR", "Fetch", "EventSource" -> true;
            default -> false;
        };
    }

    /** Whether a response body of this type can be stored as text at all. */
    static boolean isTextualContent(String mimeType) {
        if (mimeType == null || mimeType.isBlank()) {
            return false;
        }
        String lower = mimeType.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("text/")
                || lower.contains("json")
                || lower.contains("xml")
                || lower.contains("x-www-form-urlencoded");
    }
}
