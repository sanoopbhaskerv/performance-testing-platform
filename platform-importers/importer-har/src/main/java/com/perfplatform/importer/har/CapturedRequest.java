package com.perfplatform.importer.har;

import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * One entry of a capture, read into the shape the importer reasons about.
 *
 * <p>Reading is kept apart from judging so that {@link HarImporter} is about what is kept, dropped
 * and reported, rather than about where in a HAR each field lives.
 *
 * <p>Everything here is verbatim from the capture, including the parts that will be refused. The
 * refusing happens later and in one place, so there is a single answer to "what does this platform
 * do with a captured cookie".
 *
 * @param journeyRef      the capture's page reference, used to group entries into journeys
 * @param method          the HTTP method
 * @param host            the host the browser called, used to separate the application under test
 *                        from third parties, and then discarded
 * @param path            the request path
 * @param query           query parameters, decoded
 * @param headers         request headers as captured, credentials included
 * @param cookieCount     how many cookies the capture recorded; the values are never read
 * @param bodyContentType the request body's media type, or null
 * @param body            the request body as captured, or null
 * @param status          the response status the capture recorded
 * @param responseType    the response content type, used to recognise static assets
 * @param responseHeaders response headers as captured, used only as review evidence
 * @param responseBody    response body text as captured, used only to propose reviewed
 *                        correlations. It is never imported into a specification
 */
record CapturedRequest(
        String journeyRef,
        String method,
        String host,
        String path,
        Map<String, String> query,
        Map<String, String> headers,
        int cookieCount,
        String bodyContentType,
        String body,
        int status,
        String responseType,
        Map<String, String> responseHeaders,
        String responseBody) {

    /**
     * Reads one entry, or empty when it does not describe a request this platform could send.
     *
     * <p>A capture is a log, not a contract: entries are routinely truncated, aborted mid-flight or
     * written by a tool that omits fields. An unreadable entry is skipped and counted rather than
     * failing the import, because one bad entry among two hundred is not a reason to reject the
     * capture.
     */
    static Optional<CapturedRequest> from(JsonNode entry) {
        JsonNode request = entry.path("request");
        String url = text(request.get("url"));
        String method = text(request.get("method"));
        if (url == null || method == null) {
            return Optional.empty();
        }

        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
        String rawPath = uri.getRawPath();
        String path = rawPath == null || rawPath.isBlank() ? "/" : rawPath;

        JsonNode postData = request.path("postData");
        String body = text(postData.get("text"));
        String bodyType = text(postData.get("mimeType"));

        return Optional.of(new CapturedRequest(
                text(entry.get("pageref")),
                method.toUpperCase(Locale.ROOT),
                uri.getHost(),
                path,
                readNameValuePairs(request.path("queryString")),
                readNameValuePairs(request.path("headers")),
                request.path("cookies").isArray() ? request.path("cookies").size() : 0,
                bodyType,
                body,
                entry.path("response").path("status").asInt(0),
                text(entry.path("response").path("content").get("mimeType")),
                readNameValuePairs(entry.path("response").path("headers")),
                text(entry.path("response").path("content").get("text"))));
    }

    /**
     * Reads a HAR {@code [{name, value}]} list.
     *
     * <p>Repeated names keep the <em>first</em> value. A specification's headers and query are maps,
     * so a repeated name has to collapse; taking the first matches what a server reading a single
     * value would see, and the alternative - silently keeping the last - would change the request in
     * a way nobody could see from the draft.
     */
    private static Map<String, String> readNameValuePairs(JsonNode array) {
        Map<String, String> pairs = new LinkedHashMap<>();
        if (!array.isArray()) {
            return pairs;
        }
        for (JsonNode pair : array) {
            String name = text(pair.get("name"));
            if (name == null) {
                continue;
            }
            pairs.putIfAbsent(name, pair.path("value").asText(""));
        }
        return pairs;
    }

    /** Whether the capture recorded a successful exchange. */
    boolean succeeded() {
        return status >= 200 && status < 400;
    }

    /** Whether this entry fetched an asset rather than exercising the application. */
    boolean isAsset() {
        return StaticAssets.isAsset(path, responseType);
    }

    /** Identity for collapsing repeats: the same call, however many times it was made. */
    String signature() {
        return journeyRef + "\u0000" + method + "\u0000" + path;
    }

    private static String text(JsonNode node) {
        return node == null || !node.isTextual() || node.asText().isBlank() ? null : node.asText();
    }
}
