package com.perfplatform.api.v2.dto;

import com.perfplatform.domain.recorder.RecorderSanitizedCapture;

import java.util.List;
import java.util.Map;

/** Sanitized browser capture material safe to render or convert into an authoring draft. */
public record SanitizedRecorderCaptureResponse(
        String method,
        String url,
        Map<String, String> requestHeaders,
        String requestContentType,
        String requestBody,
        Map<String, String> responseHeaders,
        String responseContentType,
        String responseBody,
        List<ValidationResponse.Finding> findings) {

    public static SanitizedRecorderCaptureResponse from(RecorderSanitizedCapture capture) {
        return new SanitizedRecorderCaptureResponse(
                capture.method(),
                capture.url(),
                capture.requestHeaders(),
                capture.requestContentType(),
                capture.requestBody(),
                capture.responseHeaders(),
                capture.responseContentType(),
                capture.responseBody(),
                capture.findings().stream()
                        .map(ValidationResponse.Finding::from)
                        .toList());
    }
}
