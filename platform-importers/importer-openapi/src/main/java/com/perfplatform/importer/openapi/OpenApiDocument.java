package com.perfplatform.importer.openapi;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A parsed OpenAPI or Swagger document, and the two things reading one requires: knowing which
 * dialect it is, and following its internal references.
 *
 * <p>Kept apart from {@link OpenApiImporter} so that the importer reads as what it does — turn
 * operations into scenarios — rather than as tree navigation.
 *
 * <h2>Parsing instantiates nothing</h2>
 * The document arrives over HTTP from whoever pasted it. Jackson's YAML dataformat builds a data
 * tree and constructs no application types, so — unlike a YAML loader configured with an unsafe
 * constructor — a document cannot cause an object to be created by being parsed. It also reads JSON,
 * which is why one parser covers both serializations an OpenAPI document arrives in.
 *
 * <h2>External references are not followed</h2>
 * A {@code $ref} pointing outside the document would make the control plane fetch a URL named by an
 * untrusted document — a request forgery with the platform's own network position. Local references
 * are resolved; anything else is reported and left unresolved.
 */
final class OpenApiDocument {

    /** Which dialect the document is written in. They differ in enough places to be worth naming. */
    enum Dialect {
        /** OpenAPI 3.x: {@code servers}, {@code requestBody}, parameters carrying a {@code schema}. */
        OPENAPI_3,
        /** Swagger 2.0: {@code basePath}, a body parameter, parameters carrying their own type. */
        SWAGGER_2
    }

    /** Depth limit for reference chains, so a document referring to itself terminates. */
    private static final int MAX_REF_DEPTH = 16;

    /** {@code {name}} in a server URL, substituted from the server's declared defaults. */
    private static final Pattern SERVER_VARIABLE = Pattern.compile("\\{([^}]+)}");

    /** Reads the document. YAML, and JSON as a subset of it. */
    private static final ObjectMapper MAPPER = new ObjectMapper(new YAMLFactory());

    /**
     * Writes an example back out.
     *
     * <p>A separate mapper on purpose: {@link #MAPPER} would render a body example as YAML, and a
     * request body sent to a JSON API has to be JSON.
     */
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JsonNode root;
    private final Dialect dialect;
    private boolean sawExternalRef;

    private OpenApiDocument(JsonNode root, Dialect dialect) {
        this.root = root;
        this.dialect = dialect;
    }

    /**
     * Reads a document, or returns empty when it is not one.
     *
     * <p>Never throws. A consumer pasting the wrong file is an ordinary event, and the importer's
     * contract is that a malformed document produces a finding rather than a stack trace.
     */
    static Optional<OpenApiDocument> parse(String text) {
        JsonNode root;
        try {
            root = MAPPER.readTree(text);
        } catch (Exception e) {
            return Optional.empty();
        }
        if (root == null || !root.isObject()) {
            return Optional.empty();
        }

        if (root.hasNonNull("openapi")) {
            return Optional.of(new OpenApiDocument(root, Dialect.OPENAPI_3));
        }
        if (root.hasNonNull("swagger")) {
            return Optional.of(new OpenApiDocument(root, Dialect.SWAGGER_2));
        }
        return Optional.empty();
    }

    Dialect dialect() {
        return dialect;
    }

    JsonNode root() {
        return root;
    }

    JsonNode paths() {
        JsonNode paths = root.path("paths");
        return paths.isObject() ? paths : MAPPER.createObjectNode();
    }

    /** Whether a reference outside this document was seen and deliberately not followed. */
    boolean sawExternalRef() {
        return sawExternalRef;
    }

    /**
     * The node itself, or what its {@code $ref} points at within this document.
     *
     * <p>Returns a missing node when the reference is external or unresolvable, so callers treat an
     * unfollowable reference exactly as they treat an absent one — which is what it is, from the
     * point of view of what can be imported.
     */
    JsonNode resolve(JsonNode node) {
        JsonNode current = node;
        for (int depth = 0; depth < MAX_REF_DEPTH; depth++) {
            if (current == null || !current.isObject() || !current.hasNonNull("$ref")) {
                return current == null ? MAPPER.missingNode() : current;
            }
            String ref = current.get("$ref").asText();
            if (!ref.startsWith("#/")) {
                sawExternalRef = true;
                return MAPPER.missingNode();
            }
            current = root.at(JsonPointer.compile(ref.substring(1)));
            if (current.isMissingNode()) {
                return current;
            }
        }
        // A reference cycle. Unresolvable is the honest answer; looping is not.
        return MAPPER.missingNode();
    }

    /**
     * The path prefix every operation sits under, or empty.
     *
     * <p>The host is dropped — where a run's traffic goes is resolved from the team's registered
     * targets — but the <em>path</em> part of a server URL is not addressing, it is part of the
     * request. An API served at {@code https://api.example.com/v1} whose document declares
     * {@code /orders} is called at {@code /v1/orders}, and dropping the prefix would produce a
     * draft that 404s on every request.
     */
    String basePath() {
        String raw = dialect == Dialect.SWAGGER_2
                ? root.path("basePath").asText("")
                : firstServerUrl();

        if (raw == null || raw.isBlank()) {
            return "";
        }

        String path = raw;
        if (path.contains("://")) {
            try {
                path = new URI(path).getRawPath();
            } catch (URISyntaxException e) {
                return "";
            }
        }
        if (path == null || path.isBlank() || path.equals("/") || path.contains("{")) {
            // An unsubstituted variable means the prefix is not knowable from the document. Reported
            // by the importer; guessing at it would be inventing addressing.
            return "";
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    /** Whether a base path was declared but could not be resolved to a literal prefix. */
    boolean basePathUnresolved() {
        String raw = dialect == Dialect.SWAGGER_2
                ? root.path("basePath").asText("")
                : firstServerUrl();
        return raw != null && raw.contains("{") && basePath().isEmpty();
    }

    /** The host the document names, for the finding that reports dropping it. */
    Optional<String> declaredHost() {
        String raw = dialect == Dialect.SWAGGER_2
                ? root.path("host").asText("")
                : firstServerUrl();
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        if (!raw.contains("://")) {
            // Swagger 2's `host` is a bare authority; an OpenAPI 3 relative server URL names no host.
            return dialect == Dialect.SWAGGER_2 ? Optional.of(raw) : Optional.empty();
        }
        try {
            return Optional.ofNullable(new URI(raw).getHost());
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }

    /**
     * The first declared server URL, with its variables replaced by the defaults the document states.
     *
     * <p>The first, not a merge of all of them: a document listing production and staging servers is
     * describing where it <em>can</em> be reached, and the platform does not take that decision from
     * a document in any case. Only the path prefix survives.
     */
    private String firstServerUrl() {
        JsonNode servers = root.path("servers");
        if (!servers.isArray() || servers.isEmpty()) {
            return "";
        }
        JsonNode server = servers.get(0);
        String url = server.path("url").asText("");
        JsonNode variables = server.path("variables");
        if (url.isEmpty() || !variables.isObject()) {
            return url;
        }

        Matcher matcher = SERVER_VARIABLE.matcher(url);
        StringBuilder substituted = new StringBuilder();
        while (matcher.find()) {
            String replacement = variables.path(matcher.group(1)).path("default").asText(null);
            matcher.appendReplacement(substituted,
                    Matcher.quoteReplacement(replacement != null ? replacement : matcher.group(0)));
        }
        matcher.appendTail(substituted);
        return substituted.toString();
    }

    /** Compact JSON for a node, used when an example is an object or array. */
    static String toCompactJson(JsonNode node) {
        try {
            return JSON.writeValueAsString(node);
        } catch (Exception e) {
            return null;
        }
    }
}
