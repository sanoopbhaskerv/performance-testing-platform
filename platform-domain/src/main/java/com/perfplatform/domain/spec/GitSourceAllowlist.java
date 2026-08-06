package com.perfplatform.domain.spec;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The repositories this deployment will fetch a Playwright test from.
 *
 * <h2>Why an allowlist and not a URL field</h2>
 * A git-pinned source is the one place a specification names something the platform will go and
 * fetch, then execute. Everywhere else the author states <em>what</em> and the platform decides
 * <em>where</em> — a test cannot choose its own target host, and that is what stops it being pointed
 * somewhere it should not go (CLAUDE.md §20). A repository URL taken at face value would be a hole
 * straight through that: it reaches an arbitrary host from inside the runner cell, and what comes
 * back is code that runs.
 *
 * <p>So the same rule applies. An operator declares the repositories a team may import from, and the
 * specification chooses among them.
 *
 * <h2>Fail closed</h2>
 * A deployment that has declared nothing allows nothing. That is deliberate and it is the opposite
 * of the usual default: an empty allowlist is not "no restriction", it is "this deployment has not
 * decided yet", and the honest answer to a fetch request under those conditions is no. The finding
 * says exactly that, so the refusal reads as a configuration gap rather than as a rejected test.
 */
public record GitSourceAllowlist(Set<String> repositories) {

    private static final GitSourceAllowlist NONE = new GitSourceAllowlist(Set.of());

    public GitSourceAllowlist {
        Set<String> normalized = new LinkedHashSet<>();
        for (String repository : repositories) {
            String value = normalize(repository);
            if (!value.isEmpty()) {
                normalized.add(value);
            }
        }
        repositories = Set.copyOf(normalized);
    }

    public static GitSourceAllowlist of(List<String> repositories) {
        return repositories == null || repositories.isEmpty()
                ? NONE
                : new GitSourceAllowlist(new LinkedHashSet<>(repositories));
    }

    /** No repository may be imported from. */
    public static GitSourceAllowlist none() {
        return NONE;
    }

    /** Whether an operator has declared anything at all. */
    public boolean isConfigured() {
        return !repositories.isEmpty();
    }

    public boolean allows(String repository) {
        return repositories.contains(normalize(repository));
    }

    /**
     * Compared after normalization so that the three ways of writing the same repository match.
     *
     * <p>{@code https://github.com/Org/Repo.git}, {@code https://github.com/org/repo} and a trailing
     * slash are one repository, and an allowlist that matched only the exact spelling an operator
     * happened to type would refuse the right repository for a reason nobody could see. Only the
     * scheme, host and trailing form are folded — the path's case is preserved on hosts that treat
     * it as significant, so this lowercases the whole thing only after the host, where git forges
     * are case-insensitive in practice.
     */
    private static String normalize(String repository) {
        if (repository == null) {
            return "";
        }
        String value = repository.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.endsWith(".git")) {
            value = value.substring(0, value.length() - 4);
        }
        return value.toLowerCase(Locale.ROOT);
    }
}
