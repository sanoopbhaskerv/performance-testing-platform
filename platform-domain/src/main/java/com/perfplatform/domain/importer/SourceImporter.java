package com.perfplatform.domain.importer;

import com.perfplatform.domain.spec.PerformanceTestSpecification.ScenarioSpec;
import com.perfplatform.domain.spec.SourceType;
import com.perfplatform.domain.validation.ValidationResult;

import java.util.List;
import java.util.Objects;

/**
 * Turns something a consumer already has into draft scenarios.
 *
 * <p>An importer's job is to remove the blank page, not to produce a finished test. CLAUDE.md §23
 * is explicit that every importer yields a <em>draft</em> plus findings — because the interesting
 * part of a performance test is the part a document cannot tell you: which journeys matter, what
 * mix they run in, what data they need, and what "acceptable" means.
 *
 * <h2>What an importer must never do</h2>
 * <ul>
 *   <li><b>Invent business data.</b> An OpenAPI schema says a field is a string; it does not say
 *       which string. Inventing one produces a test that exercises validation-failure paths at
 *       scale and reports them as latency. Where data is needed and unknown, say so in a finding
 *       and leave it for a person.</li>
 *   <li><b>Import a credential.</b> Sources are full of them — an {@code Authorization} header in a
 *       cURL command, a token variable in a Postman collection, cookies in a HAR. They are stripped,
 *       and their absence is reported, so the consumer replaces them with a secret reference rather
 *       than discovering at run time that authentication is missing.</li>
 *   <li><b>Execute anything.</b> A cURL command is parsed, never run. A Postman script is flagged,
 *       never evaluated. The control plane does not run code that arrived in a document.</li>
 *   <li><b>Choose a target.</b> A source names a host; where a run's traffic goes is resolved from
 *       the team's registered targets. An importer records the host it saw as a finding and drops
 *       it, because honouring it would be a way around the target policy.</li>
 * </ul>
 */
public interface SourceImporter {

    /** The kind of source this importer understands. */
    SourceType sourceType();

    /**
     * The outcome of an import.
     *
     * <p>Scenarios and findings together, always. An import that produced scenarios but discarded
     * what it could not represent would hand someone a test that looks complete and is not — which
     * is worse than one that plainly needs work.
     *
     * @param scenarios              draft scenarios, in the order the source presented them
     * @param findings               what a person needs to know: what was stripped, what was
     *                               guessed at, what still needs deciding. An import can succeed
     *                               with findings and can produce no scenarios at all
     * @param correlationSuggestions candidate correlations with evidence. They are suggestions,
     *                               never edits to the scenarios; a person must accept one before it
     *                               becomes an extraction and replacement in the specification
     */
    record ImportResult(
            List<ScenarioSpec> scenarios,
            ValidationResult findings,
            List<CorrelationSuggestion> correlationSuggestions) {

        public ImportResult {
            scenarios = scenarios == null ? List.of() : List.copyOf(scenarios);
            Objects.requireNonNull(findings, "findings");
            correlationSuggestions = correlationSuggestions == null
                    ? List.of()
                    : List.copyOf(correlationSuggestions);
        }

        public ImportResult(List<ScenarioSpec> scenarios, ValidationResult findings) {
            this(scenarios, findings, List.of());
        }

        public static ImportResult of(List<ScenarioSpec> scenarios, ValidationResult findings) {
            return new ImportResult(scenarios, findings);
        }

        public static ImportResult of(List<ScenarioSpec> scenarios, ValidationResult findings,
                List<CorrelationSuggestion> correlationSuggestions) {
            return new ImportResult(scenarios, findings, correlationSuggestions);
        }

        /** Nothing could be imported. The findings explain why. */
        public static ImportResult nothing(ValidationResult findings) {
            return new ImportResult(List.of(), findings);
        }

        /**
         * Whether anything usable came out.
         *
         * <p>Distinct from whether the findings contain errors: an import can yield good scenarios
         * and still report that three of them need request bodies before they will do anything.
         */
        public boolean isEmpty() {
            return scenarios.isEmpty();
        }
    }

    /**
     * A correlation the importer can justify from the source.
     *
     * <p>The importer does not mutate the scenario, because correlating the wrong value is worse
     * than leaving it hard-coded: the journey still looks wired up and sends the wrong thing.
     * Evidence names the response that supplied the value and every later request location where
     * the captured value appears.
     */
    record CorrelationSuggestion(
            String id,
            String scenarioName,
            String variableName,
            ExtractionEvidence extraction,
            List<ReplacementEvidence> replacements) {

        public CorrelationSuggestion {
            replacements = replacements == null ? List.of() : List.copyOf(replacements);
        }
    }

    /** Where the suggested value came from. */
    record ExtractionEvidence(
            String stepName,
            String from,
            String expression,
            String valuePreview) {
    }

    /** Where the suggested value is reused later in the same journey. */
    record ReplacementEvidence(
            String stepName,
            String location,
            String name,
            String currentValue) {
    }

    /**
     * Imports one source document.
     *
     * <p>Must not throw for a malformed document. A consumer pasting the wrong file is an ordinary
     * event and deserves a finding that says so, not a stack trace — and an exception here would
     * surface as a 500 from an endpoint whose entire purpose is to explain what is wrong.
     *
     * @param document the source, as text
     */
    ImportResult importFrom(String document);
}
