package com.perfplatform.domain.validation;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Rejects credential <em>values</em> where only a credential <em>reference</em> belongs.
 *
 * <p>A specification is stored in the database, rendered in the UI, committed to Git by teams
 * using the GitOps channel, and echoed back by the validate endpoint. A secret pasted into one
 * is therefore not merely misplaced — it is disclosed, and rotating it becomes the only remedy.
 * Catching it at validation time is the last point where the platform can refuse cheaply.
 *
 * <p>The rule is deliberately conservative in one direction only: it recognizes the approved
 * reference schemes and treats anything else in a credential field as a value. A false positive
 * costs a consumer one clear error message; a false negative leaks a live credential.
 *
 * <p>Detection is structural, never content-based on the suspected secret. This class must not
 * log, echo or embed the offending value in a finding message — the finding says <em>that</em> a
 * field holds a value, never what the value is.
 */
public final class SecretValueDetector {

    /** Reference schemes a {@code secretRef} may use. Extended when a secret manager is approved. */
    private static final List<String> APPROVED_SCHEMES = List.of(
            "vault://",
            "awssecrets://",
            "azurekeyvault://",
            "k8s://",
            "env://",
            "run://");

    /**
     * Shapes that indicate a literal credential rather than a reference. Used to give a more
     * specific message when a field is obviously a token rather than a mistyped reference.
     */
    private static final List<Pattern> LITERAL_SHAPES = List.of(
            Pattern.compile("^[A-Za-z0-9._-]*\\.[A-Za-z0-9._-]*\\.[A-Za-z0-9._-]+$"), // JWT
            Pattern.compile("^(?i)(bearer|basic)\\s+\\S+$"),                           // auth header
            Pattern.compile("^(?i)(gh[pousr]_|xox[baprs]-|sk-|AKIA)[A-Za-z0-9/_-]+$"), // known prefixes
            Pattern.compile("^[A-Za-z0-9+/]{32,}={0,2}$"));                            // long opaque blob

    private SecretValueDetector() {
    }

    /**
     * Checks one credential-bearing field.
     *
     * @param value the authored value
     * @param path  where it lives, used in the finding
     * @return findings; empty when the value is an approved reference
     */
    public static ValidationResult check(String value, String path) {
        if (value == null || value.isBlank()) {
            return ValidationResult.of(ValidationFinding.error(
                    "spec.secret.missing",
                    path,
                    "A secret reference is required.",
                    "Provide a reference such as vault://performance/<app>/<credential>. "
                            + "Never provide the credential itself."));
        }

        String trimmed = value.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);

        for (String scheme : APPROVED_SCHEMES) {
            if (lower.startsWith(scheme)) {
                return hasReferencePath(trimmed, scheme)
                        ? ValidationResult.valid()
                        : ValidationResult.of(ValidationFinding.error(
                                "spec.secret.reference.incomplete",
                                path,
                                "The secret reference names a provider but no path.",
                                "Use the form " + scheme + "<path>, for example "
                                        + scheme + "performance/<app>/<credential>."));
            }
        }

        boolean looksLikeLiteral = LITERAL_SHAPES.stream()
                .anyMatch(pattern -> pattern.matcher(trimmed).matches());

        // The message never includes the value itself: findings are logged and returned over HTTP.
        return ValidationResult.of(ValidationFinding.error(
                "spec.secret.inline-value",
                path,
                looksLikeLiteral
                        ? "This field appears to contain a credential value. Specifications must "
                                + "contain secret references only."
                        : "This field must be a secret reference, not a literal value.",
                "Store the credential in the approved secret manager and reference it, for "
                        + "example vault://performance/<app>/<credential>. Supported schemes: "
                        + String.join(", ", APPROVED_SCHEMES)));
    }

    /** True when a reference carries a non-empty path after its scheme. */
    private static boolean hasReferencePath(String value, String scheme) {
        return value.length() > scheme.length() && !value.substring(scheme.length()).isBlank();
    }

    /**
     * Whether a value in a field that should <em>not</em> hold a credential looks like one.
     *
     * <p>The mirror of {@link #check}: that one asks "is this a proper reference?" of a field meant
     * for credentials, this asks "is this a credential?" of a field meant for anything else — a
     * header, a query parameter, a request body. Both exist because a specification is stored,
     * rendered, committed to Git and echoed back by the validate endpoint, so a secret in one is
     * disclosed rather than merely misplaced.
     *
     * <p>Deliberately narrow. This runs over ordinary values like {@code application/json} and
     * {@code GBP}, so it matches only shapes that are recognisably credentials. Broad heuristics
     * here would produce false positives on legitimate content, and a validator people learn to work
     * around protects nothing.
     */
    public static boolean looksLikeCredential(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String trimmed = value.trim();
        return LITERAL_SHAPES.stream().anyMatch(pattern -> pattern.matcher(trimmed).matches());
    }

    /**
     * Header names that carry credentials by definition and are refused outright.
     *
     * <p>Refused by <em>name</em>, whatever the value: unlike the shape check, this cannot produce a
     * false negative. Authentication belongs in {@code spec.authentication} as a reference the runner
     * resolves at execution time — that is the whole point of the secret-reference model, and a
     * header is a way around it.
     */
    private static final List<String> CREDENTIAL_HEADERS = List.of(
            "authorization",
            "proxy-authorization",
            "cookie",
            "set-cookie",
            "x-api-key",
            "x-auth-token",
            "x-amz-security-token");

    /** Whether a header carries credentials by definition, regardless of its value. */
    public static boolean isCredentialHeader(String headerName) {
        return headerName != null
                && CREDENTIAL_HEADERS.contains(headerName.trim().toLowerCase(Locale.ROOT));
    }

    /** Header names refused outright, for error messages and documentation. */
    public static List<String> credentialHeaders() {
        return CREDENTIAL_HEADERS;
    }

    /** The reference schemes currently accepted, for error messages and documentation. */
    public static List<String> approvedSchemes() {
        return APPROVED_SCHEMES;
    }
}
