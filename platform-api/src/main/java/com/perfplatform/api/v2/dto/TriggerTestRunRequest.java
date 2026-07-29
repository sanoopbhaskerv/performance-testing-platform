package com.perfplatform.api.v2.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * The body of a trigger request.
 *
 * <p>Deliberately minimal. It names an immutable version and nothing else: no team (that comes from
 * the version, so a caller cannot spend another team's capacity), no load parameters (those are the
 * version's, and a run that could override them would not be reproducible), and no target
 * (specifying one per run would route around the allowlist the version was validated against).
 *
 * <p>Runtime overrides are a later increment and will be an explicit, restricted set — never a free
 * map merged into the specification.
 *
 * @param definitionVersionId the immutable version to execute
 */
@Schema(description = "Request to execute an immutable test definition version")
public record TriggerTestRunRequest(

        @Schema(description = "Identifier of the immutable definition version to run",
                example = "3f2b1c8e-5d4a-4a1b-9c7e-2f6d8b0a1e33",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "definitionVersionId is required")
        String definitionVersionId) {
}
