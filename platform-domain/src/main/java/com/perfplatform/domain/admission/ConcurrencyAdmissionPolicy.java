package com.perfplatform.domain.admission;

import com.perfplatform.domain.identity.TeamId;

/**
 * The default policy: a global ceiling and a per-team ceiling, both hard.
 *
 * <p>The per-team limit is the one that does the interesting work. Without it a single team
 * submitting ten runs would occupy every global slot, and nine other teams would wait behind it —
 * the platform would be technically within its limits and useless to everyone else. The limit
 * caps how much of the shared platform any one team can hold at once, which is what makes the
 * queue fair without needing a scheduler that reasons about priority.
 *
 * <p>The global limit is checked first so that a full platform reports "full" rather than
 * attributing the wait to the requesting team, which would send them looking at their own runs.
 */
public final class ConcurrencyAdmissionPolicy implements AdmissionPolicy {

    @Override
    public AdmissionDecision evaluate(TeamId teamId, CapacityLimits limits, CapacitySnapshot capacity) {
        if (capacity.globalActive() >= limits.globalMaxRuns()) {
            return AdmissionDecision.defer(AdmissionDecision.GLOBAL_LIMIT,
                    "The platform is at its limit of " + limits.globalMaxRuns()
                            + " concurrent runs. This run keeps its queue position.");
        }

        int teamLimit = limits.limitFor(teamId);
        int teamActive = capacity.activeFor(teamId);
        if (teamActive >= teamLimit) {
            return AdmissionDecision.defer(AdmissionDecision.TEAM_LIMIT,
                    "This team already has " + teamActive + " of its " + teamLimit
                            + " permitted concurrent runs executing. This run keeps its queue position.");
        }

        return AdmissionDecision.admit();
    }
}
