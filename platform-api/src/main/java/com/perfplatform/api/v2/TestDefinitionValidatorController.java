package com.perfplatform.api.v2;

import com.perfplatform.api.v2.dto.ValidatorResponse;
import com.perfplatform.api.v2.security.CallerContextProvider;
import com.perfplatform.api.v2.spec.SpecificationValidationService;
import com.perfplatform.api.v2.validator.SpecificationDryRunService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The studio Validator: one live iteration before a load run exists.
 */
@RestController
@RequestMapping("/api/v2/test-definitions")
@Tag(name = "Test Definitions (V2)",
        description = "Canonical PerformanceTest specification authoring")
public class TestDefinitionValidatorController {

    private static final String YAML = "application/yaml";
    private static final String YAML_LEGACY = "application/x-yaml";
    private static final String TEXT_YAML = "text/yaml";

    private final SpecificationValidationService validationService;
    private final SpecificationDryRunService dryRun;
    private final CallerContextProvider callers;

    public TestDefinitionValidatorController(SpecificationValidationService validationService,
            SpecificationDryRunService dryRun, CallerContextProvider callers) {
        this.validationService = validationService;
        this.dryRun = dryRun;
        this.callers = callers;
    }

    /**
     * Runs one iteration against the resolved target without creating a run.
     */
    @Operation(
            summary = "Validate a specification against the real target",
            description = "Runs semantic validation first, then one protocol iteration against the "
                    + "server-side resolved target. Creates no definition, run or artifact, and "
                    + "does not consume a load-test admission slot.")
    @PostMapping(
            value = "/validator",
            consumes = {MediaType.APPLICATION_JSON_VALUE, YAML, YAML_LEGACY, TEXT_YAML,
                    MediaType.TEXT_PLAIN_VALUE},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ValidatorResponse> validateTarget(
            @RequestBody(required = false) String document) {

        SpecificationValidationService.ValidationOutcome outcome =
                validationService.validate(document);
        ValidatorResponse response = outcome.specification()
                .map(specification -> dryRun.validate(specification, outcome.findings(), callers.current()))
                .orElseGet(() -> dryRunResponse(outcome));
        return ResponseEntity.ok(response);
    }

    private static ValidatorResponse dryRunResponse(
            SpecificationValidationService.ValidationOutcome outcome) {
        return new ValidatorResponse(false, null,
                outcome.findings().findings().stream()
                        .map(com.perfplatform.api.v2.dto.ValidationResponse.Finding::from)
                        .toList(),
                java.util.List.of());
    }
}
