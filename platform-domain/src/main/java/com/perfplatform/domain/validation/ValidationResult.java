package com.perfplatform.domain.validation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The outcome of validating a specification: an ordered, deduplicated set of findings.
 *
 * <p>Results from separate validation layers are combined with {@link #merge}, so a caller runs
 * schema, semantic, policy and engine-capability validation and reports once. Findings are sorted
 * by severity then path, giving a stable order — a consumer diffing two validation runs should
 * see real changes, not reordering.
 */
public record ValidationResult(List<ValidationFinding> findings) {

    private static final Comparator<ValidationFinding> ORDER =
            Comparator.comparing(ValidationFinding::severity, Comparator.reverseOrder())
                    .thenComparing(ValidationFinding::path)
                    .thenComparing(ValidationFinding::code);

    public ValidationResult {
        findings = findings == null ? List.of() : normalize(findings);
    }

    public static ValidationResult valid() {
        return new ValidationResult(List.of());
    }

    public static ValidationResult of(ValidationFinding... findings) {
        return new ValidationResult(List.of(findings));
    }

    public static ValidationResult of(List<ValidationFinding> findings) {
        return new ValidationResult(findings);
    }

    /** Combines this result with another. Neither input is modified. */
    public ValidationResult merge(ValidationResult other) {
        if (other == null || other.findings.isEmpty()) {
            return this;
        }
        List<ValidationFinding> combined = new ArrayList<>(this.findings);
        combined.addAll(other.findings);
        return new ValidationResult(combined);
    }

    /**
     * True when nothing blocks. Warnings and info findings do not make a specification invalid —
     * a consumer should be told about a questionable weight distribution without being stopped.
     */
    public boolean isValid() {
        return findings.stream().noneMatch(ValidationFinding::isBlocking);
    }

    public List<ValidationFinding> errors() {
        return bySeverity(Severity.ERROR);
    }

    public List<ValidationFinding> warnings() {
        return bySeverity(Severity.WARNING);
    }

    public List<ValidationFinding> bySeverity(Severity severity) {
        return findings.stream().filter(finding -> finding.severity() == severity).toList();
    }

    /** The stable finding codes present, for assertions and CI rules. */
    public List<String> codes() {
        return findings.stream().map(ValidationFinding::code).toList();
    }

    /**
     * Deduplicates identical findings and applies a stable order. Two validation layers can
     * legitimately notice the same problem; reporting it twice is noise.
     */
    private static List<ValidationFinding> normalize(List<ValidationFinding> findings) {
        return findings.stream().distinct().sorted(ORDER).toList();
    }
}
