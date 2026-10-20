package com.perfplatform.domain.port;

import com.perfplatform.domain.identity.TeamId;

import java.util.Optional;

/**
 * Resolves a team identifier back to its name.
 *
 * <p>The reverse of {@link TeamDirectory}, and separate from it because it exists for one narrow
 * reason: adapters that bridge to V1 configuration are keyed by team <em>name</em>, while everything
 * inside the platform works in identifiers. Rather than let those adapters reach into persistence,
 * they ask this.
 *
 * <p>It disappears when V1's team YAML does.
 */
public interface TargetDirectory {

    /** The name of the team with this identifier, or empty if there is none. */
    Optional<String> teamName(TeamId teamId);
}
