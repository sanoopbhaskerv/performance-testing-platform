package com.perfplatform.orchestration.recorder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.perfplatform.domain.recorder.RecorderSanitizedCapture;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders a recording as a HAR 1.2 document.
 *
 * <p>This exists so that a recording is imported by the <em>same</em> code path as a HAR someone
 * exported from DevTools. That is worth an intermediate format: {@code HarImporter} already strips
 * static assets, drops failed exchanges and third-party hosts, removes negotiated and session
 * headers, groups requests into journeys, derives weights from observed frequency and proposes
 * correlations with evidence. A second importer reading captures directly would have to reproduce
 * all of it, and would drift from it the first time either changed.
 *
 * <p>A recording <em>is</em> a capture. HAR is the format captures come in. The conversion is a
 * dozen field mappings; the alternative was a parallel importer.
 *
 * <h2>What this does not do</h2>
 *
 * <p>No sanitizing. The captures arriving here have already been through
 * {@link com.perfplatform.domain.recorder.RecorderCaptureSanitizer}, and the importer strips again
 * on the way out. Doing it a third time here would put the same decision in three places and let
 * them disagree.
 *
 * <p>No timings, no cache, no cookies. A HAR carries far more than an importer reads, and inventing
 * plausible values for fields nothing consumes would make the document look like a browser export
 * that could be replayed. It cannot be, and should not look like it can.
 */
public final class RecorderHarRenderer {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String CREATOR = "perfplatform-recorder";
    private static final String HAR_VERSION = "1.2";

    private RecorderHarRenderer() {
    }

    /**
     * @param captures sanitized exchanges, in the order they were recorded
     * @return a HAR document; entries are empty when the recording captured nothing
     */
    public static String render(List<LiveRecorderSession.CapturedExchange> captures) {
        ObjectNode log = JSON.createObjectNode();
        log.put("version", HAR_VERSION);
        log.set("creator", JSON.createObjectNode().put("name", CREATOR).put("version", "1"));

        ArrayNode entries = log.putArray("entries");
        // Insertion-ordered, and the value is the first URL seen in that journey — which becomes
        // its name. Ordering matters: "first" has to mean first recorded, not first hashed.
        Map<String, String> journeys = new LinkedHashMap<>();

        if (captures != null) {
            for (LiveRecorderSession.CapturedExchange exchange : captures) {
                RecorderSanitizedCapture capture = exchange.capture();
                if (capture == null || capture.url() == null || capture.url().isBlank()) {
                    continue;
                }
                String journeyRef = journeyRefOf(capture);
                journeys.putIfAbsent(journeyRef, capture.url());
                entries.add(entry(capture, journeyRef, exchange.capturedAt().toString()));
            }
        }

        ArrayNode pages = log.putArray("pages");
        journeys.forEach((journeyRef, firstUrl) -> pages.add(JSON.createObjectNode()
                .put("id", journeyRef)
                // The title becomes the journey's name, and a real capture's title is a URL, so the
                // importer already knows to strip the scheme and host and keep only the path. That
                // turns "page-2" into "api-students" — a name a person can recognise in a report
                // without renaming three journeys by hand first.
                .put("title", firstUrl)
                .put("startedDateTime", "1970-01-01T00:00:00.000Z")));

        return JSON.createObjectNode().set("log", log).toString();
    }

    private static ObjectNode entry(RecorderSanitizedCapture capture, String journeyRef,
            String startedAt) {

        ObjectNode entry = JSON.createObjectNode();
        entry.put("pageref", journeyRef);
        entry.put("startedDateTime", startedAt);

        ObjectNode request = entry.putObject("request");
        request.put("method", capture.method() == null ? "GET" : capture.method());
        request.put("url", capture.url());
        request.set("headers", nameValuePairs(capture.requestHeaders()));
        request.set("queryString", queryString(capture.url()));
        // Always empty. The sanitizer removes cookies, and an empty array says the recorder looked
        // rather than that the field was forgotten.
        request.putArray("cookies");
        if (capture.requestBody() != null && !capture.requestBody().isBlank()) {
            request.putObject("postData")
                    .put("mimeType", capture.requestContentType() == null
                            ? "application/octet-stream" : capture.requestContentType())
                    .put("text", capture.requestBody());
        }

        ObjectNode response = entry.putObject("response");
        // A recorded status of 0 means the browser never reported one. Treating that as a success
        // would let an exchange nobody observed complete become a journey step.
        response.put("status", capture.status());
        response.set("headers", nameValuePairs(capture.responseHeaders()));
        ObjectNode content = response.putObject("content");
        if (capture.responseContentType() != null) {
            content.put("mimeType", capture.responseContentType());
        }
        if (capture.responseBody() != null) {
            content.put("text", capture.responseBody());
        }
        return entry;
    }

    private static ArrayNode nameValuePairs(Map<String, String> values) {
        ArrayNode array = JSON.createArrayNode();
        if (values != null) {
            values.forEach((name, value) -> array.add(
                    JSON.createObjectNode().put("name", name).put("value", value == null ? "" : value)));
        }
        return array;
    }

    /**
     * Splits the URL's query into HAR's name/value form.
     *
     * <p>The importer reads {@code queryString} rather than re-parsing the URL, so a query left only
     * in the URL would be silently dropped from the draft.
     */
    private static ArrayNode queryString(String url) {
        ArrayNode array = JSON.createArrayNode();
        String query;
        try {
            query = URI.create(url).getRawQuery();
        } catch (RuntimeException e) {
            return array;
        }
        if (query == null || query.isBlank()) {
            return array;
        }
        for (String pair : query.split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            int equals = pair.indexOf('=');
            String name = equals < 0 ? pair : pair.substring(0, equals);
            String value = equals < 0 ? "" : pair.substring(equals + 1);
            array.add(JSON.createObjectNode()
                    .put("name", decode(name))
                    .put("value", decode(value)));
        }
        return array;
    }

    private static String decode(String value) {
        try {
            return java.net.URLDecoder.decode(value, java.nio.charset.StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            return value;
        }
    }

    /** Everything belongs to a journey; a capture with no page reference joins the first. */
    private static String journeyRefOf(RecorderSanitizedCapture capture) {
        return capture.journeyRef() == null || capture.journeyRef().isBlank()
                ? "page-1"
                : capture.journeyRef();
    }
}
