package com.perfplatform.api.v2.dto;

import com.perfplatform.domain.recorder.RecorderCapture;

import java.util.Map;

/** Captured browser exchange submitted for sanitization. */
public record RecorderCaptureRequest(
        String method,
        String url,
        Map<String, String> requestHeaders,
        String requestContentType,
        String requestBody,
        Map<String, String> responseHeaders,
        String responseContentType,
        String responseBody,
        String storageStateJson) {

    public RecorderCapture toDomain() {
        return new RecorderCapture(method, url, requestHeaders, requestContentType, requestBody,
                responseHeaders, responseContentType, responseBody, storageStateJson);
    }
}
