package com.perfplatform.domain.recorder;

import java.util.Map;

/** Raw browser-capture material before it is eligible for storage or draft generation. */
public record RecorderCapture(
        String method,
        String url,
        Map<String, String> requestHeaders,
        String requestContentType,
        String requestBody,
        Map<String, String> responseHeaders,
        String responseContentType,
        String responseBody,
        String storageStateJson) {
}
