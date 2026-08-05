package com.perfplatform.domain.recorder;

import com.perfplatform.domain.validation.ValidationFinding;

import java.util.List;
import java.util.Map;

/** Capture material after secrets, cookies, unsafe bodies and local browser state have been removed. */
public record RecorderSanitizedCapture(
        String method,
        String url,
        Map<String, String> requestHeaders,
        String requestContentType,
        String requestBody,
        Map<String, String> responseHeaders,
        String responseContentType,
        String responseBody,
        List<ValidationFinding> findings) {

    public RecorderSanitizedCapture {
        requestHeaders = requestHeaders == null ? Map.of() : Map.copyOf(requestHeaders);
        responseHeaders = responseHeaders == null ? Map.of() : Map.copyOf(responseHeaders);
        findings = findings == null ? List.of() : List.copyOf(findings);
    }
}
