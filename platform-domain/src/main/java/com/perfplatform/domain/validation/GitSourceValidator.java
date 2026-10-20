package com.perfplatform.domain.validation;

import com.perfplatform.domain.spec.GitSourceAllowlist;
import com.perfplatform.domain.spec.PerformanceTestSpecification;
import com.perfplatform.domain.spec.PerformanceTestSpecification.SourceSpec;
import com.perfplatform.domain.spec.SourceType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Policy checks on a git-pinned Playwright source.
 *
 * <h2>Why this is not in {@link SemanticValidator}</h2>
 * {@code SemanticValidator} answers "is this document internally coherent" from the document alone,
 * which is what lets it run in a pull request with nothing configured. Whether a repository may be
 * fetched is a property of the <em>deployment</em>, not of the document, and mixing the two would
 * make the same specification valid or invalid depending on where it was checked without saying so.
 * This runs alongside, with the deployment's allowlist passed in — the same shape as
 * {@link EngineCapabilityValidator}, which asks the equivalent question about engines.
 *
 * <p>Shape is already {@code SemanticValidator}'s job: repository present, path present and free of
 * {@code ..}, ref an immutable commit SHA. What is added here is where the repository may point.
 */
public final class GitSourceValidator {

    private GitSourceValidator() {
    }

    public static ValidationResult validate(PerformanceTestSpecification specification,
            GitSourceAllowlist allowlist) {

        // A document missing its `spec` block is already rejected structurally. Policy validation
        // runs alongside that rather than after it, so it has to tolerate a half-mapped
        // specification and say nothing rather than throw.
        if (specification == null || specification.spec() == null
                || specification.spec().sources() == null) {
            return ValidationResult.valid();
        }

        List<ValidationFinding> findings = new ArrayList<>();
        List<SourceSpec> sources = specification.spec().sources();

        for (int index = 0; index < sources.size(); index++) {
            SourceSpec source = sources.get(index);
            if (source.type() != SourceType.PLAYWRIGHT || source.git() == null) {
                continue;
            }
            validateRepository(source.git().repository(),
                    "/spec/sources/" + index + "/git/repository", allowlist, findings);
        }
        return ValidationResult.of(findings);
    }

    private static void validateRepository(String repository, String path,
            GitSourceAllowlist allowlist, List<ValidationFinding> findings) {

        if (repository == null || repository.isBlank()) {
            // SemanticValidator already reported the absence; saying it twice helps nobody.
            return;
        }

        String value = repository.trim();

        String lower = value.toLowerCase(Locale.ROOT);

        /*
         * Credentials first, because this is the check that must never be skipped.
         *
         * `https://x-access-token:ghp_live@github.com/org/repo` is a working clone URL and a secret
         * written into a versioned specification — the one thing CLAUDE.md §20 forbids without
         * qualification. It would be stored, digested, echoed in findings and read by anyone with
         * definition access.
         *
         * The rule differs by scheme, and it has to. Over ssh a bare username is how everybody
         * clones — `ssh://git@host/org/repo`, `git@host:org/repo` — so only userinfo carrying a
         * password (`user:secret@`) is a credential. Over https there is no legitimate username:
         * a lone `ghp_...@github.com` is a token, which is precisely the shape this must catch.
         */
        int scheme = value.indexOf("://");
        String authority = scheme == -1 ? value : value.substring(scheme + 3);
        int slash = authority.indexOf('/');
        String host = slash == -1 ? authority : authority.substring(0, slash);
        int at = host.lastIndexOf('@');
        if (at >= 0) {
            boolean ssh = lower.startsWith("ssh://") || lower.startsWith("git@");
            boolean hasPassword = host.substring(0, at).contains(":");
            if (!ssh || hasPassword) {
                findings.add(ValidationFinding.error("spec.source.git.repository.credential", path,
                        "The repository URL carries credentials.",
                        "Remove the user and token from the URL. A specification holds references, "
                                + "never secret values; the runner's git credential is supplied by "
                                + "the platform."));
                return;
            }
        }
        if (!lower.startsWith("https://") && !lower.startsWith("ssh://") && !lower.startsWith("git@")) {
            findings.add(ValidationFinding.error("spec.source.git.repository.scheme", path,
                    "Repository '" + value + "' is not an https or ssh URL.",
                    "Use https:// or ssh://. A plaintext or local-filesystem clone URL would let "
                            + "imported material come from somewhere unverifiable."));
            return;
        }

        if (!allowlist.isConfigured()) {
            findings.add(ValidationFinding.error("spec.source.git.repository.noneAllowed", path,
                    "This deployment has no allowlisted repositories, so no git-pinned Playwright "
                            + "source can be imported.",
                    "An operator must declare the permitted repositories in "
                            + "platform.orchestration.playwright.allowed-repositories before this "
                            + "test can be validated or run."));
            return;
        }

        if (!allowlist.allows(value)) {
            findings.add(ValidationFinding.error("spec.source.git.repository.notAllowed", path,
                    "Repository '" + value + "' is not on this deployment's allowlist.",
                    "Ask an operator to add it, or pin the source to a repository that is already "
                            + "permitted. A test cannot choose where its code is fetched from, for "
                            + "the same reason it cannot choose its own target host."));
        }
    }
}
