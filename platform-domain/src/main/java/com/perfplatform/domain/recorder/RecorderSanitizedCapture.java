package com.perfplatform.domain.recorder;

import com.perfplatform.domain.validation.ValidationFinding;

import java.util.List;
import java.util.Map;

/**
 * Capture material after secrets, cookies, unsafe bodies and local browser state have been removed.
 *
 * <p>{@code status} and {@code journeyRef} survive sanitizing because neither is sensitive and both
 * are needed to turn a recording into a draft: one decides whether an exchange is worth replaying,
 * the other groups requests into journeys.
 */
public record RecorderSanitizedCapture(
        String method,
        String url,
        Map<String, String> requestHeaders,
        String requestContentType,
        String requestBody,
        Map<String, String> responseHeaders,
        String responseContentType,
        String responseBody,
        List<ValidationFinding> findings,
        int status,
        String journeyRef) {

    public RecorderSanitizedCapture {
        requestHeaders = requestHeaders == null ? Map.of() : Map.copyOf(requestHeaders);
        responseHeaders = responseHeaders == null ? Map.of() : Map.copyOf(responseHeaders);
        findings = findings == null ? List.of() : List.copyOf(findings);
    }

    public RecorderSanitizedCapture(String method, String url, Map<String, String> requestHeaders,
            String requestContentType, String requestBody, Map<String, String> responseHeaders,
            String responseContentType, String responseBody, List<ValidationFinding> findings) {
        this(method, url, requestHeaders, requestContentType, requestBody, responseHeaders,
                responseContentType, responseBody, findings, 0, null);
    }
}
