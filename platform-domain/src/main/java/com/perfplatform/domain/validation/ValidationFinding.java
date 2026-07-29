package com.perfplatform.domain.validation;

import java.util.Optional;

/**
 * One thing validation has to say about a specification.
 *
 * <p>Findings are the platform's contract with CI: a pipeline decides whether to fail a build by
 * reading {@link #code} and {@link #severity}, so codes are stable identifiers and must not be
 * renamed once released. The human-readable {@link #message} may be reworded freely.
 *
 * @param code        stable machine-readable identifier, e.g. {@code spec.workload.missing}
 * @param severity    whether this blocks
 * @param path        JSON-pointer-style location in the specification, e.g.
 *                    {@code /spec/workloads/0/model}; empty for a whole-document finding
 * @param message     what is wrong, in the consumer's language
 * @param remediation how to fix it, when there is a concrete answer; otherwise null
 */
public record ValidationFinding(
        String code,
        Severity severity,
        String path,
        String message,
        String remediation) {

    public ValidationFinding {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Finding code must not be null or blank");
        }
        if (severity == null) {
            throw new IllegalArgumentException("Finding severity must not be null");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("Finding message must not be null or blank");
        }
        path = path == null ? "" : path;
    }

    public static ValidationFinding error(String code, String path, String message, String remediation) {
        return new ValidationFinding(code, Severity.ERROR, path, message, remediation);
    }

    public static ValidationFinding warning(String code, String path, String message, String remediation) {
        return new ValidationFinding(code, Severity.WARNING, path, message, remediation);
    }

    public static ValidationFinding info(String code, String path, String message) {
        return new ValidationFinding(code, Severity.INFO, path, message, null);
    }

    public Optional<String> remediationText() {
        return Optional.ofNullable(remediation);
    }

    public boolean isBlocking() {
        return severity == Severity.ERROR;
    }
}
