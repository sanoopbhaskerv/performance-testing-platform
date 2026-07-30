package com.perfplatform.api.v2.dto;

import com.perfplatform.domain.results.ObjectiveOutcome;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The verdict on one objective, as a consumer sees it.
 *
 * <p>{@code observed} and {@code threshold} are in the objective's own units — milliseconds for a
 * latency, hundredths of a percent for a rate — and {@code detail} says which, in words. Returning
 * the raw numbers without the sentence would make a caller guess the unit; returning only the
 * sentence would make the values unusable in a dashboard.
 */
@Schema(description = "The verdict on one objective")
public record ObjectiveOutcomeResponse(
        String objectiveId,
        String metric,
        String scope,
        @Schema(description = "PASS, BREACH, or INDETERMINATE when it could not be judged at all")
        String verdict,
        long observed,
        long threshold,
        String detail) {

    public static ObjectiveOutcomeResponse from(ObjectiveOutcome outcome) {
        return new ObjectiveOutcomeResponse(
                outcome.objectiveId(),
                outcome.metric(),
                outcome.scope(),
                outcome.verdict().name(),
                outcome.observed(),
                outcome.threshold(),
                outcome.detail());
    }
}
