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
            List<StepResult> steps,
            List<CorrelationProof> correlations) {

        public JourneyResult {
            steps = steps == null ? List.of() : List.copyOf(steps);
            correlations = correlations == null ? List.of() : List.copyOf(correlations);
        }

        /** A journey whose correlations were not examined. */
        public JourneyResult(String name, boolean passed, List<StepResult> steps) {
            this(name, passed, steps, List.of());
        }
    }

    /**
     * Whether one correlation rule did anything.
     *
     * <p>Distinct from the extraction result on a step, which answers only "did a value come out".
     * A rule can produce a value, be applied to nothing, and leave the journey sending the literal
     * it was supposed to replace — a state in which every step passes and the correlation is
     * decorative. This says which of those actually happened.
     *
     * @param variable    the {@code ${name}} the rule defines
     * @param fromStep    the step whose response supplies it
     * @param usedBySteps the later steps that send it, in order. Empty means the rule is dead
     * @param verdict     one of {@code proven}, {@code unproven}, {@code used}, {@code unused},
     *                    {@code not-extracted}
     * @param livePreview a redacted preview of what this iteration extracted, or null
     * @param message     why the verdict is what it is, in a sentence a consumer can act on
     */
    public record CorrelationProof(
            String variable,
            String fromStep,
            List<String> usedBySteps,
            String verdict,
            String livePreview,
            String message) {

        public CorrelationProof {
            usedBySteps = usedBySteps == null ? List.of() : List.copyOf(usedBySteps);
        }
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
