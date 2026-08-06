package com.perfplatform.api.v2;

import com.perfplatform.api.v2.dto.ValidationResponse;
import com.perfplatform.api.v2.dto.ValidatorResponse;
import com.perfplatform.api.v2.security.CallerContextProvider;
import com.perfplatform.api.v2.spec.SpecificationValidationService;
import com.perfplatform.api.v2.validator.CorrelationBaseline;
import com.perfplatform.api.v2.validator.SpecificationDryRunService;
import com.perfplatform.domain.identity.RecorderSessionId;
import com.perfplatform.orchestration.recorder.ManageRecordingsUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The Validator, run against the recording a draft came from.
 *
 * <h2>Why this is not just the ordinary Validator</h2>
 * {@code POST /api/v2/test-definitions/validator} already runs one live iteration and reports
 * request, response, extraction and assertion evidence. What it cannot do is judge a
 * <em>correlation</em>, because a specification no longer contains the literal a correlation rule
 * replaced — accepting the rule is what removed it.
 *
 * <p>That matters because the recorder's whole authoring loop ends in an unchecked claim. The
 * importer proposes a correlation with evidence, a person accepts it, and the draft is saved. The
 * ordinary Validator then passes the journey whether or not the acceptance rewrote anything, since
 * the captured literal is usually still valid minutes later. The rule surfaces at load, as an error
 * rate, in a report somebody has to interpret.
 *
 * <p>This endpoint reads the recording the draft came from, recovers those literals, and hands them
 * to the same dry run. Each correlation then comes back with a verdict rather than a silence.
 *
 * <p>It creates nothing — no definition, no run, no artifact — and consumes no admission slot,
 * exactly like the endpoint it delegates to.
 */
@RestController
@RequestMapping("/api/v2/recordings")
@Tag(name = "Recordings (V2)", description = "Governed browser-recording authoring sessions")
public class RecordingValidatorController {

    private static final String YAML = "application/yaml";
    private static final String YAML_LEGACY = "application/x-yaml";
    private static final String TEXT_YAML = "text/yaml";

    private final ManageRecordingsUseCase recordings;
    private final SpecificationValidationService validationService;
    private final SpecificationDryRunService dryRun;
    private final CallerContextProvider callers;

    public RecordingValidatorController(ManageRecordingsUseCase recordings,
            SpecificationValidationService validationService, SpecificationDryRunService dryRun,
            CallerContextProvider callers) {
        this.recordings = recordings;
        this.validationService = validationService;
        this.dryRun = dryRun;
        this.callers = callers;
    }

    @Operation(
            summary = "Validate a drafted specification against the recording it came from",
            description = "Runs the same single live iteration as the ordinary Validator, and "
                    + "additionally judges every correlation against the value the recording "
                    + "captured: proven when the live value differs, unproven when it does not, "
                    + "and an error when the rule is extracted but sent by no later step. "
                    + "Creates no definition, run or artifact.")
    @PostMapping(
            value = "/{sessionId}/validator",
            consumes = {MediaType.APPLICATION_JSON_VALUE, YAML, YAML_LEGACY, TEXT_YAML,
                    MediaType.TEXT_PLAIN_VALUE},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ValidatorResponse> validateAgainstRecording(
            @PathVariable String sessionId,
            @RequestBody(required = false) String document) {

        SpecificationValidationService.ValidationOutcome outcome =
                validationService.validate(document);

        // Checked on validity, not merely on whether the document parsed. A malformed document
        // still maps to a specification carrying error findings, and the dry run refuses those
        // anyway — so going to the recorder first would be an authorized read of somebody's
        // captured session performed on behalf of a request that was never going to execute.
        if (outcome.specification().isEmpty() || !outcome.findings().isValid()) {
            return ResponseEntity.ok(withoutExecuting(outcome));
        }

        // Authorization happens here, in the recorder use case, on the recording's own team — the
        // dry run then re-checks the team named in the document. Both are needed: they can differ,
        // and a caller entitled to one is not thereby entitled to the other.
        CorrelationBaseline baseline = CorrelationBaseline.from(
                recordings.draft(RecorderSessionId.of(sessionId), callers.current()));

        return ResponseEntity.ok(dryRun.validate(outcome.specification().get(), outcome.findings(),
                callers.current(), baseline));
    }

    private static ValidatorResponse withoutExecuting(
            SpecificationValidationService.ValidationOutcome outcome) {
        return new ValidatorResponse(false, null,
                outcome.findings().findings().stream()
                        .map(ValidationResponse.Finding::from)
                        .toList(),
                List.of());
    }
}
