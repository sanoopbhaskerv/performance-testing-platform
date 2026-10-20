package com.perfplatform.domain.port;

import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.target.TargetAddress;

import java.util.Optional;

/**
 * Resolves where a run's traffic should go.
 *
 * <p>A port because the answer is deployment-specific and must never come from the specification.
 * A document that named its own target would route around whatever allowlist it was validated
 * against, so the platform decides: given a team, an application and an environment, this returns
 * the address the runner is permitted to address, or nothing.
 *
 * <p>Returning empty is a valid and expected answer. A run whose target cannot be resolved is
 * refused rather than pointed somewhere plausible — pointing a thousand virtual users at a guessed
 * host is the single worst thing this platform could do by accident.
 */
public interface TargetResolver {

    /**
     * @param teamId          the run's owning team
     * @param applicationName the application under test
     * @param environment     the environment named in the specification, e.g. {@code performance}
     * @return the permitted address, or empty if none is registered for this combination
     */
    Optional<TargetAddress> resolve(TeamId teamId, String applicationName, String environment);
}
