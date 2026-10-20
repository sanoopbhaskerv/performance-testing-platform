package com.perfplatform.importer.postman;

import com.fasterxml.jackson.databind.JsonNode;
import com.perfplatform.importer.postman.CollectionVariables.Resolution;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A collection's URL, read into the path and query a scenario can carry.
 *
 * <p>Postman stores a URL twice — once as {@code raw} text and once broken into {@code host},
 * {@code path} and {@code query} arrays — and the structured form is preferred here because its
 * query values are unencoded and its disabled parameters are marked. The raw form is the fallback,
 * and the only form when a collection writes the URL as a plain string.
 *
 * <p>Path and query are resolved <em>separately</em> so that an unresolved placeholder can be
 * reported differently in each. One left in a path means every request 404s; one left in a query
 * usually means a filter is missing. They are not the same problem and do not deserve the same
 * severity.
 *
 * <h2>The host is read only to be reported</h2>
 * Where a run's traffic goes is resolved from the team's registered targets. But a
 * {@code {{baseUrl}}} that resolves to {@code https://api.example.com/v1} carries a path prefix, and
 * that <em>is</em> part of the request — dropping it would produce a draft that 404s on everything.
 */
record PostmanUrl(
        String host,
        String path,
        Map<String, String> query,
        Resolution pathResolution,
        Resolution queryResolution,
        int disabledSkipped) {

    /** Reads whichever form the collection used. */
    static Optional<PostmanUrl> read(JsonNode url, CollectionVariables variables) {
        if (url == null || url.isMissingNode() || url.isNull()) {
            return Optional.empty();
        }
        if (url.isTextual()) {
            return fromRaw(url.asText(), variables, 0);
        }
        if (!url.isObject()) {
            return Optional.empty();
        }
        return url.path("path").isArray()
                ? fromParts(url, variables)
                : fromRaw(url.path("raw").asText(""), variables, 0);
    }

    /** The structured form: {@code host}, {@code path}, {@code query} and {@code variable} arrays. */
    private static Optional<PostmanUrl> fromParts(JsonNode url, CollectionVariables variables) {
        Map<String, String> pathVariables = readPathVariables(url.path("variable"), variables);

        StringBuilder rawPath = new StringBuilder();
        for (JsonNode segment : url.path("path")) {
            String value = segment.isTextual() ? segment.asText() : segment.path("value").asText("");
            if (value.isBlank()) {
                continue;
            }
            // Postman's own path-variable syntax. The value lives in url.variable, not in the
            // collection's variable list.
            rawPath.append('/').append(value.startsWith(":")
                    ? pathVariables.getOrDefault(value.substring(1), value)
                    : value);
        }

        Resolution pathResolution = variables.resolve(
                rawPath.length() == 0 ? "/" : rawPath.toString());
        // A ':name' the collection never gave a value reads exactly like an unresolved placeholder,
        // because that is what it is.
        Resolution path = withColonSegments(pathResolution);

        Map<String, String> query = new LinkedHashMap<>();
        Set<String> queryRefused = new LinkedHashSet<>();
        Set<String> queryUnknown = new LinkedHashSet<>();
        Set<String> queryDynamic = new LinkedHashSet<>();
        int disabled = 0;

        for (JsonNode parameter : url.path("query")) {
            if (parameter.path("disabled").asBoolean(false)) {
                // It was switched off in Postman, so it is not part of the request anyone ran.
                disabled++;
                continue;
            }
            String key = parameter.path("key").asText(null);
            if (key == null || key.isBlank()) {
                continue;
            }
            Resolution value = variables.resolve(parameter.path("value").asText(""));
            queryRefused.addAll(value.refused());
            queryUnknown.addAll(value.unknown());
            queryDynamic.addAll(value.dynamic());
            query.put(key, value.text());
        }

        Authority authority = authorityOf(variables.resolve(joinHost(url.path("host"))).text());

        return Optional.of(new PostmanUrl(
                authority.host(),
                authority.prefix() + path.text(),
                query,
                path,
                new Resolution("", queryRefused, queryUnknown, queryDynamic),
                disabled));
    }

    /** The raw form: one string that has to be taken apart after substitution. */
    private static Optional<PostmanUrl> fromRaw(String raw, CollectionVariables variables,
            int disabled) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }

        int questionMark = raw.indexOf('?');
        String rawPath = questionMark < 0 ? raw : raw.substring(0, questionMark);
        String rawQuery = questionMark < 0 ? "" : raw.substring(questionMark + 1);

        Resolution resolvedPath = variables.resolve(rawPath);
        Resolution resolvedQuery = variables.resolve(rawQuery);

        Authority authority = authorityOf(resolvedPath.text());
        String path = authority.prefix().isEmpty() && !authority.host().isBlank()
                ? "/"
                : authority.prefix();

        return Optional.of(new PostmanUrl(
                authority.host(),
                path.isEmpty() ? "/" : path,
                parseQuery(resolvedQuery.text()),
                withColonSegments(new Resolution(path, resolvedPath.refused(),
                        resolvedPath.unknown(), resolvedPath.dynamic())),
                resolvedQuery,
                disabled));
    }

    private static Map<String, String> readPathVariables(JsonNode variables,
            CollectionVariables collection) {
        Map<String, String> values = new LinkedHashMap<>();
        if (!variables.isArray()) {
            return values;
        }
        for (JsonNode variable : variables) {
            String key = variable.path("key").asText(null);
            String value = variable.path("value").asText(null);
            if (key != null && value != null && !value.isBlank()) {
                values.put(key, collection.resolve(value).text());
            }
        }
        return values;
    }

    /** Treats a surviving {@code :name} segment as the unresolved placeholder it is. */
    private static Resolution withColonSegments(Resolution resolution) {
        Set<String> unknown = new LinkedHashSet<>(resolution.unknown());
        for (String segment : resolution.text().split("/")) {
            if (segment.startsWith(":") && segment.length() > 1) {
                unknown.add(segment.substring(1));
            }
        }
        return new Resolution(resolution.text(), resolution.refused(), unknown,
                resolution.dynamic());
    }

    /** A host, and the path prefix that came with it. */
    private record Authority(String host, String prefix) {
    }

    /**
     * Separates the address from the request.
     *
     * <p>Handles the three shapes a collection produces after substitution: a full URL, a bare
     * authority followed by a path, and a path on its own. A leading unresolved {@code {{baseUrl}}}
     * is treated as an address — which it is — and dropped with everything else about the host.
     */
    private static Authority authorityOf(String text) {
        String remainder = text.trim();

        if (remainder.startsWith("{{")) {
            int close = remainder.indexOf("}}");
            String host = close < 0 ? remainder : remainder.substring(0, close + 2);
            return new Authority(host, normalizePrefix(close < 0 ? "" : remainder.substring(close + 2)));
        }

        if (remainder.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")) {
            try {
                URI uri = new URI(remainder);
                return new Authority(uri.getHost() == null ? "" : uri.getHost(),
                        normalizePrefix(uri.getRawPath()));
            } catch (URISyntaxException e) {
                return new Authority("", normalizePrefix(remainder));
            }
        }

        if (!remainder.startsWith("/")) {
            int slash = remainder.indexOf('/');
            String first = slash < 0 ? remainder : remainder.substring(0, slash);
            if (first.contains(".") || first.contains(":")) {
                // A bare authority such as api.example.com or localhost:8080.
                return new Authority(first, normalizePrefix(slash < 0 ? "" : remainder.substring(slash)));
            }
        }
        return new Authority("", normalizePrefix(remainder));
    }

    private static String normalizePrefix(String path) {
        if (path == null || path.isBlank() || path.equals("/")) {
            return "";
        }
        String normalized = path.startsWith("/") ? path : "/" + path;
        return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1)
                : normalized;
    }

    private static String joinHost(JsonNode host) {
        if (host.isTextual()) {
            return host.asText();
        }
        if (!host.isArray()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        host.forEach(part -> parts.add(part.asText("")));
        return String.join(".", parts);
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> query = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return query;
        }
        for (String pair : rawQuery.split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            int equals = pair.indexOf('=');
            query.put(equals < 0 ? pair : pair.substring(0, equals),
                    equals < 0 ? "" : pair.substring(equals + 1));
        }
        return query;
    }
}
