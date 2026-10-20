package com.perfplatform.domain.recorder;

import java.util.Map;

/**
 * Raw browser-capture material before it is eligible for storage or draft generation.
 *
 * @param status     the response status the browser observed, or 0 when it is not known. A draft
 *                   must be able to drop exchanges that failed: replaying a request that 404'd
 *                   during recording measures the error path and reports it as latency
 * @param journeyRef an opaque label for the page this request belonged to, such as {@code page-2}.
 *                   It groups requests into candidate journeys and is deliberately <em>not</em> the
 *                   document URL, which would put a recorded address into material that is meant to
 *                   carry none
 */
public record RecorderCapture(
        String method,
        String url,
        Map<String, String> requestHeaders,
        String requestContentType,
        String requestBody,
        Map<String, String> responseHeaders,
        String responseContentType,
        String responseBody,
        String storageStateJson,
        int status,
        String journeyRef) {

    /** For callers that have no status or journey, such as a hand-submitted capture. */
    public RecorderCapture(String method, String url, Map<String, String> requestHeaders,
            String requestContentType, String requestBody, Map<String, String> responseHeaders,
            String responseContentType, String responseBody, String storageStateJson) {
        this(method, url, requestHeaders, requestContentType, requestBody, responseHeaders,
                responseContentType, responseBody, storageStateJson, 0, null);
    }
}
