package com.perfplatform.domain.recorder;

import com.perfplatform.domain.validation.SecretValueDetector;
import com.perfplatform.domain.validation.SensitiveFieldDetector;
import com.perfplatform.domain.validation.ValidationFinding;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Removes unsafe browser-capture material before anything is persisted or converted to a draft. */
public final class RecorderCaptureSanitizer {

    private static final int MAX_BODY_CHARS = 16 * 1024;
    private static final String REDACTED = "[removed by recorder sanitizer]";

    private RecorderCaptureSanitizer() {
    }

    public static RecorderSanitizedCapture sanitize(RecorderCapture capture) {
        if (capture == null) {
            throw new IllegalArgumentException("capture must not be null");
        }

        java.util.List<ValidationFinding> findings = new java.util.ArrayList<>();

        Map<String, String> requestHeaders = sanitizeHeaders(
                capture.requestHeaders(), "/request/headers", findings);
        Map<String, String> responseHeaders = sanitizeHeaders(
                capture.responseHeaders(), "/response/headers", findings);

        String requestBody = sanitizeBody(capture.requestContentType(), capture.requestBody(),
                "/request/body", findings);
        String responseBody = sanitizeBody(capture.responseContentType(), capture.responseBody(),
                "/response/body", findings);

        if (capture.storageStateJson() != null && !capture.storageStateJson().isBlank()) {
            findings.add(ValidationFinding.warning("recorder.capture.storageState.removed",
                    "/storageState",
                    "Browser storage state was removed from the capture.",
                    "Use a run-scoped session reference instead of replaying cookies or local storage."));
        }

        return new RecorderSanitizedCapture(
                normalizeMethod(capture.method()),
                sanitizeUrl(capture.url(), findings),
                requestHeaders,
                capture.requestContentType(),
                requestBody,
                responseHeaders,
                capture.responseContentType(),
                responseBody,
                findings,
                capture.status(),
                capture.journeyRef());
    }

    private static Map<String, String> sanitizeHeaders(Map<String, String> headers, String path,
            java.util.List<ValidationFinding> findings) {
        if (headers == null || headers.isEmpty()) {
            return Map.of();
        }
        Map<String, String> safe = new LinkedHashMap<>();
        headers.forEach((name, value) -> {
            String header = name == null ? "" : name.trim();
            if (header.isBlank()) {
                return;
            }
            String childPath = path + "/" + header;
            if (SecretValueDetector.isCredentialHeader(header)) {
                findings.add(ValidationFinding.warning("recorder.capture.header.removed",
                        childPath,
                        "A credential-bearing header was removed from the capture.",
                        "Bind authentication through spec.authentication and a secret reference."));
                return;
            }
            if (SecretValueDetector.looksLikeCredential(value)) {
                findings.add(ValidationFinding.warning("recorder.capture.headerValue.removed",
                        childPath,
                        "A header value looked like a credential and was removed.",
                        "Replace the value with a dataset variable or a secret reference."));
                return;
            }
            safe.put(header, truncate(value));
        });
        return Map.copyOf(safe);
    }

    private static String sanitizeBody(String contentType, String body, String path,
            java.util.List<ValidationFinding> findings) {
        if (body == null || body.isBlank()) {
            return null;
        }
        SensitiveFieldDetector.Scan scan = SensitiveFieldDetector.scan(contentType, body);
        if (!scan.isSafe()) {
            String code = switch (scan.verdict()) {
                case CREDENTIAL -> "recorder.capture.body.credential";
                case PERSONAL_DATA -> "recorder.capture.body.personalData";
                case UNSUPPORTED -> "recorder.capture.body.unsupported";
                case SAFE -> "recorder.capture.body.safe";
            };
            findings.add(ValidationFinding.warning(code, path,
                    bodyMessage(scan),
                    "Remove the body from the recording and supply approved synthetic data, "
                            + "a dataset reference or a secret reference before execution."));
            return null;
        }
        if (body.length() > MAX_BODY_CHARS) {
            findings.add(ValidationFinding.warning("recorder.capture.body.truncated", path,
                    "A large body was truncated before it became authoring material.",
                    "Review the generated draft and replace large payloads with a dataset reference."));
        }
        return truncate(body);
    }

    private static String sanitizeUrl(String url, java.util.List<ValidationFinding> findings) {
        if (url == null || url.isBlank()) {
            return "";
        }
        try {
            java.net.URI uri = java.net.URI.create(url.trim());
            if (uri.getRawUserInfo() != null) {
                findings.add(ValidationFinding.warning("recorder.capture.urlCredentials.removed",
                        "/url",
                        "Credentials embedded in the URL were removed from the capture.",
                        "Move credentials to a secret reference."));
                return new java.net.URI(uri.getScheme(), null, uri.getHost(), uri.getPort(),
                        uri.getPath(), uri.getQuery(), null).toString();
            }
            return uri.toString();
        } catch (Exception e) {
            findings.add(ValidationFinding.warning("recorder.capture.url.unparsed", "/url",
                    "The captured URL could not be parsed and was dropped.",
                    "Review the browser session and re-record the step."));
            return "";
        }
    }

    private static String normalizeMethod(String method) {
        return method == null || method.isBlank() ? "GET" : method.trim().toUpperCase(Locale.ROOT);
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= MAX_BODY_CHARS
                ? value
                : value.substring(0, MAX_BODY_CHARS);
    }

    private static String bodyMessage(SensitiveFieldDetector.Scan scan) {
        return switch (scan.verdict()) {
            case CREDENTIAL -> scan.field() == null
                    ? "A request or response body appeared to contain a credential and was removed."
                    : "A request or response body field named '" + scan.field()
                            + "' was removed with the body.";
            case PERSONAL_DATA -> scan.field() == null
                    ? "A request or response body appeared to contain personal data and was removed."
                    : "A request or response body field named '" + scan.field()
                            + "' was removed with the body.";
            case UNSUPPORTED -> "The body format is not safe to import from a browser capture.";
            case SAFE -> REDACTED;
        };
    }
}
