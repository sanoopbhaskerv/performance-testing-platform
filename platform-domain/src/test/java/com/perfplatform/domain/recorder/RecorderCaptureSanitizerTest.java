package com.perfplatform.domain.recorder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RecorderCaptureSanitizerTest {

    @Test
    @DisplayName("credential headers and browser storage are removed from captured material")
    void removesCredentialHeadersAndStorageState() {
        RecorderSanitizedCapture sanitized = RecorderCaptureSanitizer.sanitize(new RecorderCapture(
                "post",
                "https://user:secret@proposal.perf.internal/orders",
                Map.of(
                        "Authorization", "Bearer lower-env-token",
                        "Content-Type", "application/json",
                        "X-Trace-Id", "abc-123"),
                "application/json",
                "{\"orderId\":\"123\"}",
                Map.of("Set-Cookie", "JSESSIONID=abc", "Cache-Control", "no-store"),
                "application/json",
                "{\"status\":\"ok\"}",
                "{\"cookies\":[{\"name\":\"JSESSIONID\",\"value\":\"abc\"}]}"));

        assertThat(sanitized.method()).isEqualTo("POST");
        assertThat(sanitized.url()).isEqualTo("https://proposal.perf.internal/orders");
        assertThat(sanitized.requestHeaders()).containsEntry("X-Trace-Id", "abc-123");
        assertThat(sanitized.requestHeaders()).doesNotContainKeys("Authorization");
        assertThat(sanitized.responseHeaders()).doesNotContainKeys("Set-Cookie");
        assertThat(sanitized.requestBody()).contains("orderId");
        assertThat(sanitized.findings()).extracting("code")
                .contains(
                        "recorder.capture.header.removed",
                        "recorder.capture.storageState.removed",
                        "recorder.capture.urlCredentials.removed");
    }

    @Test
    @DisplayName("bodies with credential or personal-data fields are dropped whole")
    void dropsUnsafeBodiesWhole() {
        RecorderSanitizedCapture sanitized = RecorderCaptureSanitizer.sanitize(new RecorderCapture(
                "POST",
                "https://proposal.perf.internal/login",
                Map.of("Content-Type", "application/json"),
                "application/json",
                "{\"username\":\"alice\",\"password\":\"lower-env-password\"}",
                Map.of("Content-Type", "application/json"),
                "application/json",
                "{\"customer\":{\"ssn\":\"123-45-6789\"}}",
                null));

        assertThat(sanitized.requestBody()).isNull();
        assertThat(sanitized.responseBody()).isNull();
        assertThat(sanitized.findings()).extracting("code")
                .contains("recorder.capture.body.credential",
                        "recorder.capture.body.personalData");
        assertThat(sanitized.toString())
                .doesNotContain("lower-env-password")
                .doesNotContain("123-45-6789");
    }
}
