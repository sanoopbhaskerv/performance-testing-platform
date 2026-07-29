package com.perfplatform.api.v2;

import com.perfplatform.api.v2.dto.ValidationResponse;
import com.perfplatform.api.v2.spec.SpecificationValidationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * V2 test-definition endpoints.
 *
 * <p>Validation is deliberately a separate, side-effect-free endpoint. A pull request can check a
 * specification without creating anything and without generating any load, which is what makes
 * the GitOps channel usable as a status check.
 *
 * <p>The same document is accepted as YAML or JSON, because the UI, the REST API and GitOps all
 * produce the one canonical specification — only the serialization differs.
 */
@RestController
@RequestMapping("/api/v2/test-definitions")
@Tag(name = "Test Definitions (V2)",
        description = "Canonical PerformanceTest specification authoring")
public class TestDefinitionValidationController {

    private static final String YAML = "application/yaml";
    private static final String YAML_LEGACY = "application/x-yaml";
    private static final String TEXT_YAML = "text/yaml";

    private final SpecificationValidationService validationService;

    public TestDefinitionValidationController(SpecificationValidationService validationService) {
        this.validationService = validationService;
    }

    /**
     * Validates a specification without storing it or executing anything.
     *
     * <p>Always returns {@code 200 OK}, including for an invalid specification. The request was
     * understood and answered; the findings are the answer. Returning {@code 400} would conflate
     * "your document has problems" with "your request was malformed", and would force CI to treat
     * a successful validation reporting errors as a transport failure. Problems with the
     * <em>request itself</em> — an unreadable body, an unsupported content type — do produce
     * RFC 7807 problem responses.
     */
    @Operation(
            summary = "Validate a PerformanceTest specification",
            description = "Runs structural and semantic validation and returns findings. Creates "
                    + "nothing and executes nothing. Returns 200 with valid=false when the "
                    + "specification has errors.")
    @PostMapping(
            value = "/validate",
            consumes = {MediaType.APPLICATION_JSON_VALUE, YAML, YAML_LEGACY, TEXT_YAML,
                    MediaType.TEXT_PLAIN_VALUE},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ValidationResponse> validate(@RequestBody(required = false) String document) {
        SpecificationValidationService.ValidationOutcome outcome = validationService.validate(document);
        return ResponseEntity.ok(ValidationResponse.from(outcome));
    }
}
