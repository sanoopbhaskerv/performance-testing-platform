package com.perfplatform.api.v2.dto;

import com.perfplatform.domain.spec.TargetClass;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Request to register a server-approved target")
public record TargetRegistrationRequest(
        @Schema(example = "proposal-platform", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "team is required")
        String team,

        @Schema(example = "proposal-api", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "application is required")
        String application,

        @Schema(example = "performance", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "environment is required")
        String environment,

        @Schema(example = "https://proposal-perf.internal.ameriprise.com",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "baseUrl is required")
        String baseUrl,

        @Schema(description = "Defaults to NON_PRODUCTION. PRODUCTION registration is reserved "
                + "for a later approval workflow.")
        TargetClass targetClass) {
}
