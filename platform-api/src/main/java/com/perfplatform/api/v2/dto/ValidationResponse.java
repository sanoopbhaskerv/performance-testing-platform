package com.perfplatform.api.v2.dto;

import com.perfplatform.api.v2.spec.SpecificationValidationService.ValidationOutcome;
import com.perfplatform.domain.validation.ValidationFinding;

import java.util.List;

/**
 * The response body of {@code POST /api/v2/test-definitions/validate}.
 *
 * <p>A response DTO, not the domain model and never a persistence entity. V1 returns its JPA
 * entity directly, which makes the storage schema the public contract and leaks internal fields;
 * this keeps the wire format something the platform can change the domain without breaking.
 *
 * @param valid      whether the specification can be versioned and executed. Warnings do not
 *                   make it invalid
 * @param digest     the canonical digest, present only when valid. Lets a consumer tell whether
 *                   an edit actually changed the test before creating a version
 * @param summary    counts by severity, so CI can decide without walking the list
 * @param findings   every finding, errors first, in a stable order
 */
public record ValidationResponse(
        boolean valid,
        String digest,
        Summary summary,
        List<Finding> findings) {

    /** @param errors a non-zero count always means {@code valid} is false */
    public record Summary(int errors, int warnings, int info) {
    }

    /**
     * @param code stable machine-readable identifier. CI keys on this; it does not change once
     *             released, whereas {@code message} may be reworded freely
     * @param path JSON-pointer location in the submitted document
     */
    public record Finding(
            String code,
            String severity,
            String path,
            String message,
            String remediation) {

        public static Finding from(ValidationFinding finding) {
            return new Finding(
                    finding.code(),
                    finding.severity().name(),
                    finding.path(),
                    finding.message(),
                    finding.remediation());
        }
    }

    public static ValidationResponse from(ValidationOutcome outcome) {
        var findings = outcome.findings();
        return new ValidationResponse(
                outcome.isValid(),
                outcome.digest().map(digest -> digest.value()).orElse(null),
                new Summary(
                        findings.errors().size(),
                        findings.warnings().size(),
                        findings.bySeverity(com.perfplatform.domain.validation.Severity.INFO).size()),
                findings.findings().stream().map(Finding::from).toList());
    }
}
