package com.perfplatform.api.v2.validator;

import com.perfplatform.api.v2.dto.ValidatorResponse.CorrelationProof;
import com.perfplatform.domain.spec.PerformanceTestSpecification.ExtractionSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.ScenarioSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.StepSpec;
import com.perfplatform.domain.validation.ValidationFinding;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Decides whether each correlation in a journey actually did something.
 *
 * <h2>The gap this closes</h2>
 * The studio proposes correlations with evidence and a person accepts them. Until now nothing
 * checked the accepted rule afterwards. The Validator ran the journey, saw the extraction produce a
 * value, saw every assertion pass, and reported success — which it would also have done had the
 * rule been applied to nothing at all, because the literal the rule was meant to replace is usually
 * still valid a few minutes after it was recorded. The rule is then discovered at load, as an error
 * rate, by somebody reading a report.
 *
 * <p>Three questions separate the cases, and all three are answerable from one iteration:
 *
 * <ol>
 *   <li><b>Did a value come out?</b> No — the rule is broken and the step already fails.</li>
 *   <li><b>Does any later step send it?</b> No — the rule is dead. Accepting it rewrote nothing,
 *       so the journey still hard-codes the captured literal. This is an error: there is no reading
 *       of an accepted rule that changes nothing under which the journey is correct.</li>
 *   <li><b>Is the live value different from the one the recording captured?</b> Yes — the journey
 *       demonstrably carried a fresh value. No — it may be correct and it may be a replay of a
 *       still-valid literal; the two are indistinguishable, so the verdict says so rather than
 *       claiming a pass.</li>
 * </ol>
 *
 * <p>Question three needs a {@link CorrelationBaseline} and therefore a recording. Without one the
 * verdict stops at {@code used}, which is the whole truth available and no more.
 */
final class CorrelationProofEvaluator {

    /** The value resolved, was consumed downstream, and differs from what the recording sent. */
    static final String PROVEN = "proven";
    /** Resolved and consumed, but identical to the recorded literal, so it proves nothing. */
    static final String UNPROVEN = "unproven";
    /** Resolved and consumed. No recording to compare against, so nothing stronger is claimed. */
    static final String USED = "used";
    /** Resolved but sent by no later step: the rule is dead and the literal is still hard-coded. */
    static final String UNUSED = "unused";
    /** Nothing matched. The step itself already failed; this names why. */
    static final String NOT_EXTRACTED = "not-extracted";

    private final UnaryOperator<String> preview;

    /**
     * @param preview renders a value for display, redacting anything credential-shaped. Comparison
     *                happens on the raw values, so a redacted preview never weakens the verdict —
     *                which matters because a session token is exactly the value most worth proving
     */
    CorrelationProofEvaluator(UnaryOperator<String> preview) {
        this.preview = preview;
    }

    /**
     * @param scenario  the journey as specified
     * @param extracted what the live iteration pulled out, by variable name
     * @param baseline  what the recording sent for the same variables, or
     *                  {@link CorrelationBaseline#none()}
     * @param findings  collector; dead rules append an error, unproven ones a warning
     */
    List<CorrelationProof> evaluate(ScenarioSpec scenario, Map<String, String> extracted,
            CorrelationBaseline baseline, List<ValidationFinding> findings) {

        List<CorrelationProof> proofs = new ArrayList<>();
        List<StepSpec> steps = scenario.asSteps();

        for (int index = 0; index < steps.size(); index++) {
            StepSpec source = steps.get(index);
            for (ExtractionSpec extraction : source.extract()) {
                proofs.add(proofOf(scenario, steps, index, source, extraction, extracted, baseline,
                        findings));
            }
        }
        return proofs;
    }

    private CorrelationProof proofOf(ScenarioSpec scenario, List<StepSpec> steps, int sourceIndex,
            StepSpec source, ExtractionSpec extraction, Map<String, String> extracted,
            CorrelationBaseline baseline, List<ValidationFinding> findings) {

        String variable = extraction.name();
        List<String> usedBy = new ArrayList<>();
        for (int later = sourceIndex + 1; later < steps.size(); later++) {
            if (steps.get(later).referencedVariables().contains(variable)) {
                usedBy.add(steps.get(later).name());
            }
        }

        String path = "/spec/workloads/scenarios/" + scenario.name() + "/steps/" + source.name()
                + "/extract/" + variable;
        String live = extracted.get(variable);

        if (live == null) {
            return new CorrelationProof(variable, source.name(), usedBy, NOT_EXTRACTED, null,
                    "Nothing matched this extraction, so '" + variable + "' has no value to carry.");
        }

        if (usedBy.isEmpty()) {
            findings.add(ValidationFinding.error("validator.correlation.unused", path,
                    "The correlation '" + variable + "' is extracted but no later step sends it.",
                    "The journey is still sending the value that was captured, which will be stale "
                            + "under load. Re-accept the suggestion, or remove the rule if the "
                            + "value is genuinely fixed."));
            return new CorrelationProof(variable, source.name(), usedBy, UNUSED, preview.apply(live),
                    "Extracted, then sent by nothing. The steps that should use '" + variable
                            + "' are still carrying the captured value.");
        }

        Optional<String> recorded = baseline.literal(scenario.name(), variable);
        if (recorded.isEmpty()) {
            return new CorrelationProof(variable, source.name(), usedBy, USED, preview.apply(live),
                    "Extracted and sent by " + describe(usedBy) + ". There is no recording to "
                            + "compare against, so whether the value changes cannot be shown here.");
        }

        if (recorded.get().equals(live)) {
            findings.add(ValidationFinding.warning("validator.correlation.unproven", path,
                    "The correlation '" + variable + "' produced the same value the recording "
                            + "captured, so this run does not show that it works.",
                    "Expected for a value that never changes. If it should differ each time — a "
                            + "session, a token, a created id — record again and re-validate."));
            return new CorrelationProof(variable, source.name(), usedBy, UNPROVEN,
                    preview.apply(live),
                    "The same value the recording captured. A journey that ignored this rule "
                            + "entirely would look identical, so nothing is proven either way.");
        }

        return new CorrelationProof(variable, source.name(), usedBy, PROVEN, preview.apply(live),
                "A different value from the one recorded, sent by " + describe(usedBy)
                        + ". This journey carried a fresh value rather than replaying the capture.");
    }

    private static String describe(List<String> steps) {
        return steps.size() == 1 ? "'" + steps.get(0) + "'"
                : steps.size() + " later steps";
    }
}
