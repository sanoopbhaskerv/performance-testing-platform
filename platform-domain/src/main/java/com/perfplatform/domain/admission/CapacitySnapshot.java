package com.perfplatform.domain.admission;

import com.perfplatform.domain.identity.TeamId;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * How much capacity is in use right now.
 *
 * <p>A point-in-time count of runs holding a slot, taken by the store inside the same transaction
 * that claims work. Reading it outside that transaction would let two schedulers each observe eight
 * active runs and each admit two, ending at twelve.
 *
 * <p>Mutable-looking only through {@link #withAdmitted}: admission evaluates several queued runs in
 * one pass, and each provisional decision has to be visible to the next. Returning a new snapshot
 * rather than mutating keeps the accounting explicit at every step.
 *
 * @param globalActive  total runs holding a slot
 * @param activeByTeam  per-team counts; a team absent from the map has none
 */
public record CapacitySnapshot(int globalActive, Map<TeamId, Integer> activeByTeam) {

    public static final CapacitySnapshot EMPTY = new CapacitySnapshot(0, Map.of());

    public CapacitySnapshot {
        if (globalActive < 0) {
            throw new IllegalArgumentException("globalActive must not be negative");
        }
        Objects.requireNonNull(activeByTeam, "activeByTeam");
        activeByTeam = Map.copyOf(activeByTeam);
    }

    public int activeFor(TeamId teamId) {
        return activeByTeam.getOrDefault(teamId, 0);
    }

    /** The snapshot as it would be once one more run for this team is admitted. */
    public CapacitySnapshot withAdmitted(TeamId teamId) {
        Map<TeamId, Integer> updated = new HashMap<>(activeByTeam);
        updated.merge(teamId, 1, Integer::sum);
        return new CapacitySnapshot(globalActive + 1, updated);
    }
}
