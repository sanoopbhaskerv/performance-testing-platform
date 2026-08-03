package com.perfplatform.api.v2.dto;

import java.util.List;
import java.util.Map;

/**
 * Result of the studio Validator: one low-impact pass through the test before a load run exists.
 *
 * @param valid    true only when every executed step passed
 * @param target   the resolved target base URL, or null when no request was sent
 * @param findings validation, target-resolution and runtime findings
 * @param journeys per-journey evidence, in execution order
 */
public record ValidatorResponse(
        boolean valid,
        String target,
        List<ValidationResponse.Finding> findings,
        List<JourneyResult> journeys) {

    public record JourneyResult(
            String name,
            boolean passed,
            List<StepResult> steps) {
    }

    public record StepResult(
            String name,
            String method,
            String path,
            boolean passed,
            long durationMillis,
            RequestEvidence request,
            ResponseEvidence response,
            List<ExtractionResult> extractions,
            List<AssertionResult> assertions,
            String error) {
    }

    public record RequestEvidence(
            String url,
            Map<String, String> headers,
            String bodyPreview) {
    }

    public record ResponseEvidence(
            int status,
            Map<String, String> headers,
            String bodyPreview) {
    }

    public record ExtractionResult(
            String name,
            String from,
            String expression,
            boolean resolved,
            String valuePreview,
            String message) {
    }

    public record AssertionResult(
            String subject,
            String operator,
            String expected,
            boolean passed,
            String observed,
            String message) {
    }
}
