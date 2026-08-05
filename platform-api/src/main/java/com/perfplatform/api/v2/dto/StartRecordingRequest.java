package com.perfplatform.api.v2.dto;

import jakarta.validation.constraints.NotBlank;

/** Request to create a governed recorder authoring session. */
public record StartRecordingRequest(
        String team,
        String teamId,
        @NotBlank String application,
        @NotBlank String environment) {
}
