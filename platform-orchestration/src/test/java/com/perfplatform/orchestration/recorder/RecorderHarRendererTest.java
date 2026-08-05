package com.perfplatform.orchestration.recorder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.perfplatform.domain.recorder.RecorderSanitizedCapture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RecorderHarRendererTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-08-06T10:00:00Z");

    @Test
    @DisplayName("a capture becomes a HAR entry the importer can read")
    void entryShape() throws Exception {
        JsonNode log = render(capture(1, "POST", "http://app.internal/orders", 201, "page-1",
                Map.of("content-type", "application/json"), "{\"item\":1}"));

        JsonNode entry = log.path("entries").get(0);
        assertThat(entry.path("pageref").asText()).isEqualTo("page-1");
        assertThat(entry.path("request").path("method").asText()).isEqualTo("POST");
        assertThat(entry.path("request").path("url").asText())
                .isEqualTo("http://app.internal/orders");
        assertThat(entry.path("response").path("status").asInt()).isEqualTo(201);
        assertThat(entry.path("request").path("postData").path("text").asText())
                .isEqualTo("{\"item\":1}");
    }

    @Test
    @DisplayName("headers are rendered as HAR name/value pairs")
    void headersArePairs() throws Exception {
        JsonNode log = render(capture(1, "GET", "http://app.internal/x", 200, "page-1",
                Map.of("accept", "application/json"), null));

        JsonNode headers = log.path("entries").get(0).path("request").path("headers");
        assertThat(headers.isArray()).isTrue();
        assertThat(headers.get(0).path("name").asText()).isEqualTo("accept");
        assertThat(headers.get(0).path("value").asText()).isEqualTo("application/json");
    }

    /**
     * The importer reads {@code queryString} rather than re-parsing the URL, so a query present only
     * in the URL would vanish from the draft without anything saying so.
     */
    @Test
    @DisplayName("the query string is split out, not left only in the URL")
    void queryIsSplitOut() throws Exception {
        JsonNode log = render(capture(1, "GET", "http://app.internal/search?q=blue%20hat&page=2",
                200, "page-1", Map.of(), null));

        JsonNode query = log.path("entries").get(0).path("request").path("queryString");
        assertThat(query).hasSize(2);
        assertThat(query.get(0).path("name").asText()).isEqualTo("q");
        assertThat(query.get(0).path("value").asText()).isEqualTo("blue hat");
        assertThat(query.get(1).path("name").asText()).isEqualTo("page");
    }

    @Test
    @DisplayName("each page a user visited becomes a HAR page, so it becomes a journey")
    void pagesBecomeJourneys() throws Exception {
        JsonNode log = render(
                capture(1, "GET", "http://app.internal/", 200, "page-1", Map.of(), null),
                capture(2, "GET", "http://app.internal/api/a", 200, "page-1", Map.of(), null),
                capture(3, "GET", "http://app.internal/checkout", 200, "page-2", Map.of(), null));

        assertThat(log.path("pages")).hasSize(2);
        assertThat(log.path("pages").get(0).path("id").asText()).isEqualTo("page-1");
        assertThat(log.path("pages").get(1).path("id").asText()).isEqualTo("page-2");
        assertThat(log.path("entries")).hasSize(3);
    }

    /**
     * The importer names a journey from its page title, stripping scheme and host. Titling a page
     * with its first URL is what turns {@code page-2} into {@code api-orders} — a name someone can
     * recognise in a report rather than three journeys they have to rename by hand.
     */
    @Test
    @DisplayName("a journey is titled with its first request, so the importer can name it")
    void journeyIsTitledFromItsFirstRequest() throws Exception {
        JsonNode log = render(
                capture(1, "GET", "http://app.internal/api/orders", 200, "page-2", Map.of(), null),
                capture(2, "GET", "http://app.internal/api/orders/1", 200, "page-2", Map.of(), null));

        assertThat(log.path("pages").get(0).path("title").asText())
                .isEqualTo("http://app.internal/api/orders");
    }

    @Test
    @DisplayName("a capture with no page reference still belongs to a journey")
    void missingJourneyRefDefaults() throws Exception {
        JsonNode log = render(capture(1, "GET", "http://app.internal/", 200, null, Map.of(), null));

        assertThat(log.path("entries").get(0).path("pageref").asText()).isEqualTo("page-1");
    }

    /**
     * A status the browser never reported must not read as a success. The importer drops failed
     * exchanges, and defaulting an unobserved one to 200 would smuggle it into the draft.
     */
    @Test
    @DisplayName("an unobserved status stays zero rather than becoming a success")
    void unknownStatusIsNotInvented() throws Exception {
        JsonNode log = render(capture(1, "GET", "http://app.internal/", 0, "page-1", Map.of(), null));

        assertThat(log.path("entries").get(0).path("response").path("status").asInt()).isZero();
    }

    @Test
    @DisplayName("cookies are always an empty array — the recorder looked and there were none")
    void cookiesAreEmptyNotAbsent() throws Exception {
        JsonNode log = render(capture(1, "GET", "http://app.internal/", 200, "page-1", Map.of(), null));

        JsonNode cookies = log.path("entries").get(0).path("request").path("cookies");
        assertThat(cookies.isArray()).isTrue();
        assertThat(cookies).isEmpty();
    }

    @Test
    @DisplayName("a recording that captured nothing renders a valid, empty document")
    void emptyRecording() throws Exception {
        JsonNode log = JSON.readTree(RecorderHarRenderer.render(List.of())).path("log");

        assertThat(log.path("version").asText()).isEqualTo("1.2");
        assertThat(log.path("entries").isArray()).isTrue();
        assertThat(log.path("entries")).isEmpty();
    }

    private static JsonNode render(LiveRecorderSession.CapturedExchange... captures)
            throws Exception {
        return JSON.readTree(RecorderHarRenderer.render(List.of(captures))).path("log");
    }

    private static LiveRecorderSession.CapturedExchange capture(long sequence, String method,
            String url, int status, String journeyRef, Map<String, String> requestHeaders,
            String body) {

        return new LiveRecorderSession.CapturedExchange(sequence,
                new RecorderSanitizedCapture(method, url, requestHeaders,
                        body == null ? null : "application/json", body,
                        Map.of(), "application/json", null, List.of(), status, journeyRef),
                NOW);
    }
}
