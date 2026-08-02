package com.perfplatform.importer.postman;

import com.fasterxml.jackson.databind.JsonNode;
import com.perfplatform.domain.validation.SecretValueDetector;
import com.perfplatform.domain.validation.SensitiveFieldDetector;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves a collection's {@code {{placeholders}}}, and refuses the ones holding credentials.
 *
 * <p>Postman collections are written against variables — {@code {{baseUrl}}}, {@code {{orderId}}},
 * {@code {{authToken}}} — and an exported collection carries the values with it. That is convenient
 * and it is the collection's most reliable way of leaking a secret: a variable named
 * {@code accessToken} in a shared collection usually holds a working token, and unlike a HAR nobody
 * thinks of an exported collection as sensitive.
 *
 * <p>So a variable is substituted only when it is safe on both tests this platform already applies
 * elsewhere: its <b>name</b> is not one {@link SensitiveFieldDetector} recognises as a credential,
 * and its <b>value</b> is not one {@link SecretValueDetector} recognises as a credential. A variable
 * failing either is left as its placeholder and reported — never substituted, because the substituted
 * text goes into a specification that is stored, rendered and committed to Git.
 *
 * <h2>Three ways a placeholder can fail to resolve, and they are different problems</h2>
 * <ul>
 *   <li><b>Refused</b> — the collection states a value and it is a credential. Declare it under
 *       {@code spec.authentication} instead.</li>
 *   <li><b>Unknown</b> — the collection states no value, because it lives in a Postman environment
 *       that was not exported. Somebody has to supply it.</li>
 *   <li><b>Dynamic</b> — {@code {{$randomInt}}} and friends, which Postman generates per request.
 *       This platform does not evaluate them, and a dataset is the equivalent.</li>
 * </ul>
 */
final class CollectionVariables {

    /** {@code {{name}}}, the only interpolation syntax a collection uses. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([^{}]+)}}");

    private final Map<String, String> safeValues;
    private final Set<String> refusedNames;

    private CollectionVariables(Map<String, String> safeValues, Set<String> refusedNames) {
        this.safeValues = safeValues;
        this.refusedNames = refusedNames;
    }

    /**
     * Reads a collection's variable list.
     *
     * <p>Collection scope only. Folder and request scopes exist in the schema and are vanishingly
     * rare in practice; a placeholder they would have defined simply reports as unknown, which is
     * the honest answer rather than a wrong value.
     */
    static CollectionVariables from(JsonNode collection) {
        Map<String, String> safe = new LinkedHashMap<>();
        Set<String> refused = new LinkedHashSet<>();

        JsonNode variables = collection.path("variable");
        if (!variables.isArray()) {
            return new CollectionVariables(safe, refused);
        }

        for (JsonNode variable : variables) {
            String key = variable.path("key").asText(null);
            if (key == null || key.isBlank() || variable.path("disabled").asBoolean(false)) {
                continue;
            }
            String value = variable.path("value").asText(null);
            if (value == null || value.isBlank()) {
                continue;
            }
            if (!SensitiveFieldDetector.scanNames(List.of(key)).isSafe()
                    || SecretValueDetector.looksLikeCredential(value)) {
                refused.add(key);
                continue;
            }
            safe.put(key, value);
        }
        return new CollectionVariables(safe, refused);
    }

    /**
     * What substituting a piece of text produced.
     *
     * @param text      the text with every safe placeholder replaced; unsafe ones remain verbatim
     * @param refused   placeholders left in place because the collection's value is a credential
     * @param unknown   placeholders the collection states no value for
     * @param dynamic   Postman-generated placeholders such as {@code {{$guid}}}
     */
    record Resolution(String text, Set<String> refused, Set<String> unknown, Set<String> dynamic) {

        boolean isComplete() {
            return refused.isEmpty() && unknown.isEmpty() && dynamic.isEmpty();
        }

        /** Every placeholder that survived, whatever the reason. */
        Set<String> unresolved() {
            Set<String> all = new LinkedHashSet<>(refused);
            all.addAll(unknown);
            all.addAll(dynamic);
            return all;
        }
    }

    /** Substitutes what is safe to substitute, and reports the rest by category. */
    Resolution resolve(String text) {
        Set<String> refused = new LinkedHashSet<>();
        Set<String> unknown = new LinkedHashSet<>();
        Set<String> dynamic = new LinkedHashSet<>();

        if (text == null || text.isEmpty()) {
            return new Resolution(text == null ? "" : text, refused, unknown, dynamic);
        }

        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1).trim();
            String replacement;

            if (name.startsWith("$")) {
                dynamic.add(name);
                replacement = matcher.group(0);
            } else if (refusedNames.contains(name)) {
                refused.add(name);
                replacement = matcher.group(0);
            } else {
                String value = safeValues.get(name);
                if (value == null) {
                    unknown.add(name);
                    replacement = matcher.group(0);
                } else {
                    replacement = value;
                }
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);

        return new Resolution(result.toString(), refused, unknown, dynamic);
    }

    /** Whether any variable was refused, for the finding that reports it once. */
    Set<String> refusedNames() {
        return refusedNames;
    }
}
