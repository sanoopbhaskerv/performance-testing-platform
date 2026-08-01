package com.perfplatform.api.v2.dto;

import com.perfplatform.domain.importer.SourceImporter.ImportResult;
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
        List<ValidationResponse.Finding> findings) {

    /** One draft scenario. Mirrors the specification's own shape, so it can be pasted straight in. */
    @Schema(description = "A draft scenario, in the shape a specification uses")
    public record ImportedScenario(
            String name,
            int weight,
            String method,
            String path,
            Map<String, String> headers,
            Map<String, String> query,
            RequestBodyResponse body) {
    }

    @Schema(description = "A request body")
    public record RequestBodyResponse(String contentType, String content) {
    }

    public static ImportResponse from(String sourceType, ImportResult result) {
        return new ImportResponse(
                sourceType,
                result.scenarios().stream().map(ImportResponse::scenarioFrom).toList(),
                // The same Finding shape the validate endpoint returns. A second shape for one
                // concept is how the two drift apart, and a consumer already parses this one.
                result.findings().findings().stream()
                        .map(ValidationResponse.Finding::from)
                        .toList());
    }

    private static ImportedScenario scenarioFrom(ScenarioSpec scenario) {
        return new ImportedScenario(
                scenario.name(),
                scenario.weight(),
                scenario.methodOrDefault(),
                scenario.path(),
                scenario.headers(),
                scenario.query(),
                scenario.body() == null
                        ? null
                        : new RequestBodyResponse(
                                scenario.body().contentTypeOrDefault(),
                                scenario.body().content()));
    }
}
