package com.perfplatform.domain.port;

import com.perfplatform.domain.identity.ApplicationId;
import com.perfplatform.domain.identity.TeamId;

import java.util.Optional;

/**
 * Resolves a team's name to its identifier.
 *
 * <p>Needed wherever a name crosses a boundary — a specification's {@code metadata.team}, a YAML
 * file, an identity provider's group claim — and has to become the typed identifier every
 * authorization and quota decision uses.
 *
 * <p>Resolution only. Nothing here creates a team: onboarding is an administrative act, and a
 * lookup that quietly created what it could not find would let a typo in a specification manufacture
 * a team with no owner, no quota and no approval policy.
 */
public interface TeamDirectory {

    /** The team with this name, or empty if there is none. */
    Optional<TeamId> findByName(String name);

    /**
     * The application with this name within the team, or empty if there is none.
     *
     * <p>Scoped to a team because application names are unique within one, not globally: two teams
     * may each own a "proposal" application, and resolving one without its team would be ambiguous
     * in exactly the place where the answer decides whose data is touched.
     */
    Optional<ApplicationId> findApplication(TeamId teamId, String name);
}
