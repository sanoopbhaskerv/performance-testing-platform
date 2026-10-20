package com.perfplatform.api.v2.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request to create a governed recorder authoring session.
 *
 * <p>Note what is absent: a URL. The destination comes from the registered target for this team,
 * application and environment, so a caller cannot point a recording browser at an address the
 * target registry never approved (recorder security review §4.1).
 *
 * @param viewportWidth  optional; clamped server-side, never trusted
 * @param viewportHeight optional; clamped server-side, never trusted
 */
public record StartRecordingRequest(
        String team,
        String teamId,
        @NotBlank String application,
        @NotBlank String environment,
        Integer viewportWidth,
        Integer viewportHeight) {
}
