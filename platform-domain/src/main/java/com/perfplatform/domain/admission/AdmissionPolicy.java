package com.perfplatform.domain.admission;

import com.perfplatform.domain.identity.TeamId;

/**
 * Decides whether a queued run may start.
 *
 * <p>Admission is evaluated at claim time rather than at trigger time, and the distinction matters.
 * Capacity at the moment of submission says nothing about capacity twenty minutes later when a slot
 * frees; deciding early would either reject runs that could have run, or admit runs into a platform
 * that has since filled up. Trigger-time checks are for things that do not change — is the
 * specification valid, is the target permitted, is the caller authorized.
 *
 * <p>Implementations must be pure functions of their arguments. An implementation that queried the
 * database would be evaluating against a different snapshot than the one the caller locked, which
 * is precisely the race the snapshot exists to prevent.
 */
@FunctionalInterface
public interface AdmissionPolicy {

    /**
     * @param teamId   the team whose run is being considered
     * @param limits   the configured ceilings
     * @param capacity capacity in use, including any runs provisionally admitted earlier in this pass
     */
    AdmissionDecision evaluate(TeamId teamId, CapacityLimits limits, CapacitySnapshot capacity);
}
