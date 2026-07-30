package com.perfplatform.api.v2;

import com.perfplatform.api.v2.dto.TestDefinitionResponse;
import com.perfplatform.api.v2.security.CallerContextProvider;
import com.perfplatform.api.v2.spec.SpecificationParser;
import com.perfplatform.api.v2.spec.SpecificationValidationService;
import com.perfplatform.api.v2.spec.SpecificationValidationService.ValidationOutcome;
import com.perfplatform.domain.identity.TestDefinitionId;
import com.perfplatform.domain.validation.ValidationResult;
import com.perfplatform.orchestration.definition.AuthorTestDefinitionUseCase;
import com.perfplatform.orchestration.definition.AuthorTestDefinitionUseCase.AuthoredVersion;
import com.perfplatform.orchestration.definition.AuthorTestDefinitionUseCase.Submission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/**
 * Creating test definitions and appending versions.
 *
 * <p>Separate from {@link TestDefinitionValidationController}, which owns the side-effect-free
 * {@code /validate} path. Splitting them keeps the endpoint a pull request calls — the one that must
 * never create anything — visibly independent of the ones that do.
 *
 * <p>A definition's name, team and application all come from the submitted document rather than from
 * request fields. Taking them separately would let a caller store a specification saying one thing
 * under a definition saying another, and the two would drift with nothing to reconcile them.
 */
@RestController
@RequestMapping("/api/v2/test-definitions")
@Tag(name = "Test Definitions (V2)",
        description = "Canonical PerformanceTest specification authoring")
public class TestDefinitionController {

    private static final String YAML = "application/yaml";
    private static final String YAML_LEGACY = "application/x-yaml";
    private static final String TEXT_YAML = "text/yaml";

    private final AuthorTestDefinitionUseCase authoring;
    private final SpecificationValidationService validationService;
    private final SpecificationParser parser;
    private final CallerContextProvider callers;

    public TestDefinitionController(AuthorTestDefinitionUseCase authoring,
            SpecificationValidationService validationService,
            SpecificationParser parser,
            CallerContextProvider callers) {
        this.authoring = authoring;
        this.validationService = validationService;
        this.parser = parser;
        this.callers = callers;
    }

    /**
     * Creates a definition and stores the submitted specification as version 1.
     *
     * <p>A specification with errors is <b>refused</b> rather than stored. The validate endpoint
     * exists precisely so a consumer can find out before committing to anything, and a registry
     * full of definitions that can never run is a registry nobody trusts.
     */
    @Operation(
            summary = "Create a test definition",
            description = "Stores the submitted specification as version 1. The definition's name, "
                    + "team and application come from the document's metadata. Returns 422 with "
                    + "findings if the specification does not validate.")
    @PostMapping(consumes = {MediaType.APPLICATION_JSON_VALUE, YAML, YAML_LEGACY, TEXT_YAML,
            MediaType.TEXT_PLAIN_VALUE}, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TestDefinitionResponse> create(@RequestBody(required = false) String document) {
        Submission submission = validate(document);
        AuthoredVersion authored = authoring.create(submission, callers.current());

        return ResponseEntity
                .created(URI.create("/api/v2/test-definitions/" + authored.definition().id()))
                .body(TestDefinitionResponse.of(authored.definition(), authored.version(),
                        ValidationResult.valid()));
    }

    /**
     * Appends a new immutable version.
     *
     * <p>{@code 201} with the version's own identifier, because the version — not the definition —
     * is what a run references. A caller that only kept the definition identifier would have to
     * guess which version they had just created.
     */
    @Operation(
            summary = "Append a version to a test definition",
            description = "Stores a new immutable version. Existing versions are never modified.")
    @PostMapping(value = "/{definitionId}/versions",
            consumes = {MediaType.APPLICATION_JSON_VALUE, YAML, YAML_LEGACY, TEXT_YAML,
                    MediaType.TEXT_PLAIN_VALUE},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TestDefinitionResponse> appendVersion(
            @PathVariable String definitionId,
            @RequestBody(required = false) String document) {

        Submission submission = validate(document);
        AuthoredVersion authored = authoring.appendVersion(
                TestDefinitionId.of(definitionId), submission, callers.current());

        return ResponseEntity
                .created(URI.create("/api/v2/test-definitions/" + definitionId
                        + "/versions/" + authored.version().versionNumber()))
                .body(TestDefinitionResponse.of(authored.definition(), authored.version(),
                        ValidationResult.valid()));
    }

    @Operation(
            summary = "List test definitions",
            description = "Definitions belonging to the caller's teams.")
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public List<TestDefinitionResponse> list() {
        return authoring.listForCaller(callers.current()).stream()
                .map(TestDefinitionResponse::of)
                .toList();
    }

    @Operation(summary = "Get a test definition")
    @GetMapping(value = "/{definitionId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public TestDefinitionResponse get(@PathVariable String definitionId) {
        return TestDefinitionResponse.of(
                authoring.get(TestDefinitionId.of(definitionId), callers.current()));
    }

    @Operation(
            summary = "List a definition's versions",
            description = "Newest first. Each version identifier is what POST /api/v2/test-runs takes.")
    @GetMapping(value = "/{definitionId}/versions", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<TestDefinitionResponse.VersionSummary> versions(@PathVariable String definitionId) {
        return authoring.listVersions(TestDefinitionId.of(definitionId), callers.current()).stream()
                .map(TestDefinitionResponse.VersionSummary::from)
                .toList();
    }

    /**
     * Validates a submitted document and prepares it for storage.
     *
     * @throws InvalidSpecificationException if it does not validate, carrying every finding
     */
    private Submission validate(String document) {
        ValidationOutcome outcome = validationService.validate(document);
        if (!outcome.isValid()) {
            throw new InvalidSpecificationException(outcome.findings());
        }

        return new Submission(
                parser.toStoredJson(document),
                outcome.specification().orElseThrow(),
                outcome.digest().orElseThrow(),
                true);
    }

    /**
     * Raised when a submitted specification cannot be stored.
     *
     * <p>Carries the findings so the response says what is wrong rather than only that something is.
     * Defined here because it exists only to move findings from this method to the problem handler.
     */
    public static class InvalidSpecificationException extends RuntimeException {

        private final transient ValidationResult findings;

        public InvalidSpecificationException(
                ValidationResult findings) {
            super("The submitted specification did not validate");
            this.findings = findings;
        }

        public ValidationResult findings() {
            return findings;
        }
    }
}
