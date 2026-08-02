package com.perfplatform.importer.openapi;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Iterator;
import java.util.Optional;

/**
 * Values the document itself states, and nothing else.
 *
 * <p>This class is where CLAUDE.md §23's hardest rule lives: <b>do not invent valid business data
 * silently.</b> A schema saying a field is a string does not say <em>which</em> string, and an
 * importer that picks one produces a test that exercises the target's validation-failure paths at
 * full load and reports the result as latency. That is worse than no test, because it looks like
 * one.
 *
 * <p>So every value here comes from something a person wrote in the document — an {@code example},
 * an {@code examples} entry, a schema {@code example}, a {@code default}, or the first member of an
 * {@code enum}. All five are statements about a real permitted value. Nothing is derived from a
 * type, a format, a pattern or a minimum. When there is no stated value the answer is empty, and the
 * importer turns that into a finding for a person to answer.
 *
 * <p>An {@code enum}'s first member is a judgement call worth naming: it is a value the document
 * declares valid, which is exactly the test being applied — but it is a choice among several, so the
 * importer reports it rather than using it silently.
 */
final class StatedValues {

    private StatedValues() {
    }

    /** Where a value came from, so the importer can say so rather than presenting it as fact. */
    enum Origin {
        /** An {@code example} or {@code examples} entry: the document's own illustration. */
        EXAMPLE,
        /** A {@code default}: what the API uses when the caller sends nothing. */
        DEFAULT,
        /** The first member of an {@code enum}: valid, but one of several. */
        ENUM
    }

    /** A value the document states, and where it came from. */
    record Stated(String value, Origin origin) {
    }

    /**
     * The value a parameter states, if it states one.
     *
     * <p>Handles both dialects: OpenAPI 3 puts {@code default} and {@code enum} inside the
     * parameter's {@code schema}, Swagger 2 puts them on the parameter itself.
     */
    static Optional<Stated> forParameter(JsonNode parameter, OpenApiDocument document) {
        Optional<Stated> direct = fromExamples(parameter, document, Origin.EXAMPLE);
        if (direct.isPresent()) {
            return direct;
        }

        JsonNode schema = document.resolve(parameter.path("schema"));
        for (JsonNode candidate : new JsonNode[] {schema, parameter}) {
            if (candidate == null || !candidate.isObject()) {
                continue;
            }
            Optional<Stated> stated = text(candidate.get("example")).map(v -> new Stated(v, Origin.EXAMPLE))
                    .or(() -> text(candidate.get("default")).map(v -> new Stated(v, Origin.DEFAULT)))
                    .or(() -> firstEnumMember(candidate).map(v -> new Stated(v, Origin.ENUM)));
            if (stated.isPresent()) {
                return stated;
            }
        }
        return Optional.empty();
    }

    /**
     * The body a media type states, if it states one.
     *
     * <p>A schema is deliberately not turned into a body. Generating {@code {"sku":"string"}} from a
     * schema would produce a request every target rejects, at whatever load the test declares.
     */
    static Optional<Stated> forMediaType(JsonNode mediaType, OpenApiDocument document) {
        Optional<Stated> direct = fromExamples(mediaType, document, Origin.EXAMPLE);
        if (direct.isPresent()) {
            return direct;
        }
        JsonNode schema = document.resolve(mediaType.path("schema"));
        return schema != null && schema.isObject()
                ? text(schema.get("example")).map(value -> new Stated(value, Origin.EXAMPLE))
                : Optional.empty();
    }

    /** {@code example}, then the first {@code examples} entry's {@code value}. */
    private static Optional<Stated> fromExamples(JsonNode node, OpenApiDocument document,
            Origin origin) {
        if (node == null || !node.isObject()) {
            return Optional.empty();
        }

        Optional<Stated> single = text(node.get("example")).map(value -> new Stated(value, origin));
        if (single.isPresent()) {
            return single;
        }

        JsonNode examples = node.path("examples");
        if (!examples.isObject()) {
            return Optional.empty();
        }
        // The first entry, in document order. A named set of examples is a set of alternatives, and
        // choosing among them is a person's decision; the importer reports which one it took.
        Iterator<JsonNode> entries = examples.elements();
        while (entries.hasNext()) {
            JsonNode example = document.resolve(entries.next());
            Optional<Stated> value = text(example.get("value")).map(v -> new Stated(v, origin));
            if (value.isPresent()) {
                return value;
            }
        }
        return Optional.empty();
    }

    private static Optional<String> firstEnumMember(JsonNode node) {
        JsonNode members = node.path("enum");
        return members.isArray() && !members.isEmpty() ? text(members.get(0)) : Optional.empty();
    }

    /**
     * A node as the text that would be sent.
     *
     * <p>A string is sent as itself, not as a quoted JSON string — {@code example: open} means the
     * parameter value {@code open}. An object or array becomes compact JSON, which is what a body
     * example is.
     */
    private static Optional<String> text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Optional.empty();
        }
        if (node.isValueNode()) {
            String value = node.asText();
            return value.isEmpty() ? Optional.empty() : Optional.of(value);
        }
        return Optional.ofNullable(OpenApiDocument.toCompactJson(node));
    }
}
