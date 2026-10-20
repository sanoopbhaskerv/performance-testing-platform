package com.perfplatform.domain.admission;

import com.perfplatform.domain.identity.TeamId;

import java.util.Map;
import java.util.Objects;

/**
 * The concurrency limits the platform enforces.
 *
 * <p>Enforced server-side, never read from a submitted specification. A specification that could
 * raise its own limits would make the limits decorative — and the thing being limited is how much
 * simultaneous load the platform points at internal systems.
 *
 * <p>Defaults follow CLAUDE.md §13: ten material runs globally, one per team, with a per-team
 * override of two available where policy has approved it. The global figure is a ceiling for the
 * worst case, not a target; normal operation is three or four concurrent runs.
 *
 * <h2>Browser concurrency is not counted here</h2>
 * Browser sessions are governed separately and must never be equated with protocol virtual users —
 * five browsers and five hundred protocol users cost entirely different amounts. These limits count
 * material <em>runs</em>. When the Playwright engine arrives (Increment 3) it brings its own,
 * smaller session budget rather than borrowing slots from this one.
 *
 * @param globalMaxRuns    hard ceiling on simultaneously executing runs across all teams
 * @param defaultTeamMaxRuns   per-team ceiling when no override applies
 * @param teamOverrides    per-team ceilings granted by policy; absent teams use the default
 */
public record CapacityLimits(
        int globalMaxRuns,
        int defaultTeamMaxRuns,
        Map<TeamId, Integer> teamOverrides) {

    /** The CLAUDE.md §13 defaults. */
    public static final CapacityLimits DEFAULTS = new CapacityLimits(10, 1, Map.of());

    public CapacityLimits {
        if (globalMaxRuns < 1) {
            throw new IllegalArgumentException("globalMaxRuns must be at least 1");
        }
        if (defaultTeamMaxRuns < 1) {
            throw new IllegalArgumentException("defaultTeamMaxRuns must be at least 1");
        }
        if (defaultTeamMaxRuns > globalMaxRuns) {
            throw new IllegalArgumentException(
                    "defaultTeamMaxRuns (" + defaultTeamMaxRuns + ") cannot exceed globalMaxRuns ("
                            + globalMaxRuns + ")");
        }
        Objects.requireNonNull(teamOverrides, "teamOverrides");
        teamOverrides.forEach((team, limit) -> {
            if (limit == null || limit < 1) {
                throw new IllegalArgumentException(
                        "Team limit for " + team + " must be at least 1");
            }
            if (limit > globalMaxRuns) {
                throw new IllegalArgumentException(
                        "Team limit for " + team + " (" + limit + ") cannot exceed globalMaxRuns ("
                                + globalMaxRuns + ")");
            }
        });
        teamOverrides = Map.copyOf(teamOverrides);
    }

    /** The ceiling that applies to this team. */
    public int limitFor(TeamId teamId) {
        return teamOverrides.getOrDefault(teamId, defaultTeamMaxRuns);
    }
}
