package com.perfplatform.api.v2.dto;

import com.perfplatform.orchestration.run.CancelTestRunUseCase.CancellationOutcome;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The result of asking a run to stop.
 *
 * <p>The outcome is reported separately from the run's state because they answer different
 * questions. A run that is still {@code RUNNING} with an outcome of
 * {@code CANCELLATION_REQUESTED} has been asked to stop and has not stopped yet — a caller shown
 * only the state would conclude their request had been ignored and press the button again.
 *
 * @param outcome what the request achieved
 * @param run     the run as it stands now
 */
@Schema(description = "Result of a cancellation request")
public record CancellationResponse(
        @Schema(description = "CANCELLED when the run stopped immediately, "
                + "CANCELLATION_REQUESTED when a runner is still being stopped, "
                + "ALREADY_CANCELLED when the run had already been cancelled")
        String outcome,
        TestRunResponse run) {

    public static CancellationResponse from(CancellationOutcome outcome) {
        return new CancellationResponse(
                outcome.status().name(),
                TestRunResponse.from(outcome.run()));
    }
}
