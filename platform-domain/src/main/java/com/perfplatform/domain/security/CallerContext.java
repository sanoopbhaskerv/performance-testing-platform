package com.perfplatform.domain.security;

import com.perfplatform.domain.identity.TeamId;

import java.util.Set;

/**
 * Who is making a request, and what they may do.
 *
 * <p>The authorization domain, deliberately independent of how identity arrives. Corporate OIDC for
 * people and scoped service identities for CI are Increment 4 decisions (CLAUDE.md §31 makes the
 * identity provider a pause condition); this type is the seam they plug into, so the rules below are
 * written and tested now rather than retrofitted around whatever the provider turns out to emit.
 *
 * <p>Every decision combines a scope with team ownership. V1 treated any valid API key as authorized
 * for every team, which meant one team's key could read and trigger another team's runs. Requiring
 * both here makes that shape unrepresentable.
 *
 * @param subject     stable identity of the caller — a corporate identity for a person, a service
 *                    identity for CI. Recorded on runs and events, so it must be attributable to
 *                    something real
 * @param teamIds     teams this caller acts for
 * @param scopes      permissions held
 * @param serviceIdentity whether this is a machine. CI must use its own identity rather than
 *                    borrowing a person's, so that a departing employee does not silently break
 *                    a pipeline — and so an automated trigger is attributable to the pipeline
 */
public record CallerContext(
        String subject,
        Set<TeamId> teamIds,
        Set<String> scopes,
        boolean serviceIdentity) {

    public CallerContext {
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("Caller subject must not be null or blank");
        }
        teamIds = teamIds == null ? Set.of() : Set.copyOf(teamIds);
        scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
    }

    /** Whether the caller holds the scope. Administrators hold every scope implicitly. */
    public boolean hasScope(String scope) {
        return scopes.contains(Scope.PLATFORM_ADMIN) || scopes.contains(scope);
    }

    /** Whether the caller acts for this team. Administrators act for every team. */
    public boolean belongsTo(TeamId teamId) {
        return scopes.contains(Scope.PLATFORM_ADMIN) || teamIds.contains(teamId);
    }

    /**
     * Whether the caller may perform this action on this team's resources.
     *
     * <p>Both conditions, always. A caller with {@code runs:execute} for their own team must not be
     * able to start a run for another team, and a member of a team without {@code runs:execute} must
     * not be able to start one at all.
     */
    public boolean canAct(String scope, TeamId teamId) {
        return hasScope(scope) && belongsTo(teamId);
    }

    /**
     * @throws AuthorizationException if the caller may not perform this action
     */
    public void requireAct(String scope, TeamId teamId) {
        if (!canAct(scope, teamId)) {
            // The message never distinguishes "wrong team" from "missing scope": telling an
            // unauthorized caller which teams exist, or that a resource they cannot see is real,
            // is itself a disclosure.
            throw new AuthorizationException(subject, scope);
        }
    }

    /** Raised when a caller is not permitted to perform an action. */
    public static class AuthorizationException extends RuntimeException {
        private final transient String requiredScope;

        public AuthorizationException(String subject, String requiredScope) {
            super("Caller " + subject + " is not permitted to perform this action");
            this.requiredScope = requiredScope;
        }

        /** For logging only. Never returned to the caller. */
        public String requiredScope() {
            return requiredScope;
        }
    }
}
