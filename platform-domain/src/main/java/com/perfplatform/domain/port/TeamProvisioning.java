package com.perfplatform.domain.port;

import com.perfplatform.domain.identity.ApplicationId;
import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.spec.TargetClass;

/**
 * Registers teams and applications.
 *
 * <p>Deliberately separate from {@link TeamDirectory}, which only resolves names. Every read path in
 * the platform depends on the directory; almost nothing should be able to create a team, because a
 * team is the unit that owns quota, approval policy and data ownership. Keeping the write side in
 * its own narrow port means a lookup cannot quietly become a creation.
 *
 * <p>The only caller today is the migration adapter that mirrors V1's team YAML into the registry
 * during the V1/V2 overlap, and it is restricted to non-production profiles. Corporate onboarding
 * arrives with identity management in Increment 4.
 */
public interface TeamProvisioning {

    /**
     * Returns the team with this name, creating it if absent.
     *
     * @return the identifier of the existing or newly created team
     */
    TeamId ensureTeam(String name);

    /**
     * Returns the application with this name within the team, creating it if absent.
     *
     * <p>The target class is applied only on creation. An existing application's classification is
     * never changed here: it governs which targets may be tested and whether a run needs approval,
     * so nothing that merely resolves a name may weaken it.
     */
    ApplicationId ensureApplication(TeamId teamId, String name, TargetClass targetClass);
}
