package com.perfplatform.api.v2.validator;

import com.perfplatform.domain.importer.SourceImporter.CorrelationSuggestion;
import com.perfplatform.domain.importer.SourceImporter.ImportResult;
import com.perfplatform.domain.importer.SourceImporter.ReplacementEvidence;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * What a capture recorded, for each value a correlation rule replaced.
 *
 * <h2>Why the Validator needs this</h2>
 * A correlation rule can resolve, substitute and pass while proving nothing. The recorded literal
 * is often still valid at validation time — a session token minted minutes ago, an order id that
 * has not been cleaned up yet — so a journey that never applied the rule at all sends the stale
 * literal and gets a clean 200. Extraction succeeded, assertions passed, and the rule is untested.
 *
 * <p>Holding the literal the rule replaced is what makes the difference observable: if the value
 * the live iteration extracted is <em>different</em> from the one the recording captured, the
 * journey demonstrably carried a fresh value rather than a replayed one. That is the only evidence
 * available before a load run exists, and it is the evidence a person accepting a suggestion is
 * implicitly claiming to have.
 *
 * <p>Empty when a specification is validated on its own rather than against a recording. The
 * Validator then reports that an extraction resolved and was consumed, and does not claim more.
 */
public record CorrelationBaseline(Map<String, Map<String, String>> literalsByScenario) {

    private static final CorrelationBaseline NONE = new CorrelationBaseline(Map.of());

    public CorrelationBaseline {
        Map<String, Map<String, String>> copy = new LinkedHashMap<>();
        literalsByScenario.forEach((scenario, literals) -> copy.put(scenario, Map.copyOf(literals)));
        literalsByScenario = Map.copyOf(copy);
    }

    /** No recording to compare against. */
    public static CorrelationBaseline none() {
        return NONE;
    }

    /**
     * Reads the literals out of an importer's correlation suggestions.
     *
     * <p>The replacement's {@code currentValue} rather than the extraction's value preview: the
     * preview is truncated for display, while {@code currentValue} is the exact string the studio
     * rewrote to {@code ${variable}}, so it is the one thing guaranteed to equal what the journey
     * used to send.
     */
    public static CorrelationBaseline from(ImportResult recording) {
        Map<String, Map<String, String>> byScenario = new LinkedHashMap<>();
        for (CorrelationSuggestion suggestion : recording.correlationSuggestions()) {
            String literal = suggestion.replacements().stream()
                    .map(ReplacementEvidence::currentValue)
                    .filter(value -> value != null && !value.isEmpty())
                    .findFirst()
                    .orElse(null);
            if (literal == null) {
                continue;
            }
            byScenario.computeIfAbsent(suggestion.scenarioName(), key -> new LinkedHashMap<>())
                    .putIfAbsent(suggestion.variableName(), literal);
        }
        return new CorrelationBaseline(byScenario);
    }

    public boolean isEmpty() {
        return literalsByScenario.isEmpty();
    }

    /** What the recording sent for this variable, if it recorded one. */
    public Optional<String> literal(String scenarioName, String variableName) {
        return Optional.ofNullable(literalsByScenario.get(scenarioName))
                .map(literals -> literals.get(variableName));
    }
}
