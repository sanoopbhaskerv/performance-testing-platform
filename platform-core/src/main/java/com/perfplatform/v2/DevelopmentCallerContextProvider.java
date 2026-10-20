package com.perfplatform.v2;

import com.perfplatform.api.v2.security.CallerContextProvider;
import com.perfplatform.config.TeamConfig;
import com.perfplatform.config.TeamConfigLoader;
import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.port.TeamDirectory;
import com.perfplatform.domain.security.CallerContext;
import com.perfplatform.domain.security.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.Set;

/**
 * A development-only caller identity, derived from the V1 API key.
 *
 * <p><b>Not for production.</b> Corporate OIDC for people and scoped service identities for CI are
 * Increment 4, and choosing an identity provider is a pause condition (CLAUDE.md §31). The
 * composition root refuses to start with this provider active under the production profile, so the
 * gap fails loudly rather than shipping quietly.
 *
 * <h2>What it nevertheless fixes</h2>
 * V1 treats <em>any</em> valid API key as authorized for <em>every</em> team: the filter checks that
 * a key exists somewhere in the team configuration and then attaches it as the principal, with no
 * link between the key and the team whose data is being touched. This maps the presented key back to
 * the team that owns it and scopes the caller to that team alone. The V2 endpoints therefore enforce
 * team ownership from the day they exist, rather than inheriting the defect and waiting for
 * Increment 4 to remove it.
 *
 * <p>The V1 endpoints keep V1's behavior. Changing them is a separate, deliberate decision — this
 * class does not touch the filter or the V1 controller.
 */
public class DevelopmentCallerContextProvider implements CallerContextProvider {

    private static final Logger log = LoggerFactory.getLogger(DevelopmentCallerContextProvider.class);

    /**
     * Scopes a team's key confers.
     *
     * <p>Everything a team does for itself, and nothing that crosses a team boundary:
     * {@code platform:admin} is deliberately absent, so a development key cannot reach another
     * team's runs even in local use.
     */
    private static final Set<String> TEAM_SCOPES = Set.of(
            Scope.DEFINITIONS_READ,
            Scope.DEFINITIONS_WRITE,
            Scope.RUNS_EXECUTE,
            Scope.RUNS_CANCEL,
            Scope.RUNS_READ,
            Scope.RECORDINGS_CREATE,
            Scope.RECORDINGS_READ,
            Scope.RECORDINGS_CANCEL);

    private final TeamConfigLoader teamConfigs;
    private final TeamDirectory teams;

    public DevelopmentCallerContextProvider(TeamConfigLoader teamConfigs, TeamDirectory teams) {
        this.teamConfigs = teamConfigs;
        this.teams = teams;
    }

    @Override
    public CallerContext current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication.getPrincipal() == null) {
            throw new UnauthenticatedException("No authenticated caller on this request");
        }

        // The V1 filter attaches the API key itself as the principal. It is a credential, so it is
        // resolved to a team name here and never logged, echoed or used as the subject.
        String presentedKey = String.valueOf(authentication.getPrincipal());
        Optional<TeamConfig> owningTeam = teamConfigs.getAllConfigs().stream()
                .filter(config -> presentedKey.equals(config.getApiKey()))
                .findFirst();

        if (owningTeam.isEmpty()) {
            throw new UnauthenticatedException("The presented credential does not identify a team");
        }

        String teamName = owningTeam.get().getTeam();
        Optional<TeamId> teamId = teams.findByName(teamName);
        if (teamId.isEmpty()) {
            // The team exists in YAML but has not been onboarded into the V2 registry. Returning a
            // caller with no teams is correct: every authorization check then fails, which is the
            // intended outcome, rather than inventing a team the platform knows nothing about.
            log.warn("Team '{}' is configured in YAML but not registered in the V2 team registry; "
                    + "V2 requests for it will be refused", teamName);
            return new CallerContext("team:" + teamName, Set.of(), TEAM_SCOPES, true);
        }

        return new CallerContext("team:" + teamName, Set.of(teamId.get()), TEAM_SCOPES, true);
    }
}
