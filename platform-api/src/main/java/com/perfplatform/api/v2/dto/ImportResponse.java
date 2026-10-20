package com.perfplatform.api.v2.dto;

import com.perfplatform.domain.importer.SourceImporter.ImportResult;
import com.perfplatform.domain.importer.SourceImporter.CorrelationSuggestion;
import com.perfplatform.domain.spec.PerformanceTestSpecification;
import com.perfplatform.domain.spec.PerformanceTestSpecification.ScenarioSpec;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

/**
 * What an import produced.
 *
 * <p>Scenarios and findings together, always. An import that returned scenarios without saying what
 * it had stripped would hand someone a draft that looks finished and is not — and the things it
 * strips are the things that would otherwise fail at run time as a wall of 401s.
 *
 * <p>Deliberately not a whole specification. An importer supplies the part a source can actually
 * answer: which requests exist. The load model, the objectives and the journey mix are decisions
 * nobody can read out of a cURL command, and generating placeholder values for them would produce a
 * test that looks ready to run and means nothing.
 *
 * @param sourceType the kind of source that was read
 * @param scenarios  draft scenarios, ready to paste into a specification's workload
 * @param findings   what was stripped, what was assumed, and what still needs a person
 */
@Schema(description = "Draft scenarios read from a source, with everything a person needs to know "
        + "about what was changed or removed")
public record ImportResponse(
        String sourceType,
        List<ImportedScenario> scenarios,
        List<ValidationResponse.Finding> findings,
        List<ImportedCorrelationSuggestion> correlationSuggestions) {

    /**
     * One draft scenario. Mirrors the specification's own shape, so it can be pasted straight in.
     *
     * <p>{@code operationId} is carried separately from {@code name} because the two answer to
     * different rules: a name is a short lowercase identifier that ends up in artifact object keys
     * and Kubernetes labels, while an operationId is whatever the source document called the
     * operation. CLAUDE.md §23 requires preserving it, and that is what lets a report be matched
     * back to the operation in the document. Null for sources that have no such concept.
     */
    @Schema(description = "A draft scenario, in the shape a specification uses")
    public record ImportedScenario(
            String name,
            int weight,
            String operationId,
            String method,
            String path,
            Map<String, String> headers,
            Map<String, String> query,
            RequestBodyResponse body,
            /**
             * The ordered requests, when this scenario is a journey; empty when it states one
             * request directly.
             *
             * <p>Added with S3. Without it a journey would arrive at the studio as a scenario with
             * no method and no path — which is how {@code operationId} nearly stopped at this
             * boundary in 2c, and the same mistake would be harder to spot here because the
             * scenario would still look like a valid object.
             */
            List<ImportedStep> steps) {
    }

    /** One request within an imported journey. */
    public record ImportedStep(
            String name,
            String method,
            String path,
            Map<String, String> headers,
            Map<String, String> query,
            RequestBodyResponse body) {
    }

    @Schema(description = "A request body")
    public record RequestBodyResponse(String contentType, String content) {
    }

    /** A correlation suggestion with enough evidence for a person to accept or reject it. */
    public record ImportedCorrelationSuggestion(
            String id,
            String scenarioName,
            String variableName,
            ImportedExtractionEvidence extraction,
            List<ImportedReplacementEvidence> replacements) {
    }

    /** The response-side evidence for a suggested extraction. */
    public record ImportedExtractionEvidence(
            String stepName,
            String from,
            String expression,
            String valuePreview) {
    }

    /** A later request location that uses the captured value. */
    public record ImportedReplacementEvidence(
            String stepName,
            String location,
            String name,
            String currentValue) {
    }

    public static ImportResponse from(String sourceType, ImportResult result) {
        return new ImportResponse(
                sourceType,
                result.scenarios().stream().map(ImportResponse::scenarioFrom).toList(),
                // The same Finding shape the validate endpoint returns. A second shape for one
                // concept is how the two drift apart, and a consumer already parses this one.
                result.findings().findings().stream()
                        .map(ValidationResponse.Finding::from)
                        .toList(),
                result.correlationSuggestions().stream()
                        .map(ImportResponse::suggestionFrom)
                        .toList());
    }

    private static ImportedCorrelationSuggestion suggestionFrom(CorrelationSuggestion suggestion) {
        return new ImportedCorrelationSuggestion(
                suggestion.id(),
                suggestion.scenarioName(),
                suggestion.variableName(),
                new ImportedExtractionEvidence(
                        suggestion.extraction().stepName(),
                        suggestion.extraction().from(),
                        suggestion.extraction().expression(),
                        suggestion.extraction().valuePreview()),
                suggestion.replacements().stream()
                        .map(replacement -> new ImportedReplacementEvidence(
                                replacement.stepName(),
                                replacement.location(),
                                replacement.name(),
                                replacement.currentValue()))
                        .toList());
    }

    private static ImportedScenario scenarioFrom(ScenarioSpec scenario) {
        return new ImportedScenario(
                scenario.name(),
                scenario.weight(),
                scenario.operationId(),
                // A journey states no method of its own; reporting the GET default here would
                // describe a request it does not make.
                scenario.isJourney() ? null : scenario.methodOrDefault(),
                scenario.path(),
                scenario.headers(),
                scenario.query(),
                bodyFrom(scenario.body()),
                scenario.steps().stream().map(ImportResponse::stepFrom).toList());
    }

    private static ImportedStep stepFrom(PerformanceTestSpecification.StepSpec step) {
        return new ImportedStep(
                step.name(),
                step.methodOrDefault(),
                step.path(),
                step.headers(),
                step.query(),
                bodyFrom(step.body()));
    }

    private static RequestBodyResponse bodyFrom(ScenarioSpec.RequestBody body) {
        return body == null
                ? null
                : new RequestBodyResponse(body.contentTypeOrDefault(), body.content());
    }
}
