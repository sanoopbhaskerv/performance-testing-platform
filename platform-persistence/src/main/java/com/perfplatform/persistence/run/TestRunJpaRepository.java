package com.perfplatform.persistence.run;

import com.perfplatform.domain.run.RunState;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data access to run rows.
 *
 * <p>Package-private to the persistence module by convention: {@code TestRunStore} is the only
 * caller. CLAUDE.md §9 forbids API controllers from reaching a repository directly, and keeping the
 * interface out of the public surface is what makes that easy to hold to.
 */
public interface TestRunJpaRepository extends JpaRepository<TestRunEntity, UUID> {

    Optional<TestRunEntity> findByTeamIdAndIdempotencyKey(UUID teamId, String idempotencyKey);

    /**
     * Locks the candidate rows for the duration of the transaction.
     *
     * <p>{@code PESSIMISTIC_WRITE} rather than an optimistic read: two reconcilers evaluating the
     * same queued run would both pass an optimistic check and both attempt to start it, and the
     * loser's failure would arrive only after a runner had already been created.
     *
     * <p>Ordered by queue age with the identifier as a tiebreak, so ordering is deterministic and
     * auditable — "why did their run start before ours" has an answer that does not depend on the
     * database's physical row order.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT r FROM TestRunEntity r
            WHERE r.status IN :states
              AND (r.claimOwner IS NULL OR r.claimExpiresAt <= :now OR r.claimOwner = :owner)
            ORDER BY COALESCE(r.queuedAt, r.requestedAt) ASC, r.id ASC
            """)
    List<TestRunEntity> lockClaimable(@Param("states") Collection<RunState> states,
            @Param("now") Instant now,
            @Param("owner") String owner);

    /**
     * Counts runs holding a capacity slot, grouped by team.
     *
     * <p>Read inside the claim transaction, while the admission mutex is held, so the counts cannot
     * shift between being observed and being acted on.
     *
     * @return rows of {@code [teamId, count]}
     */
    @Query("""
            SELECT r.teamId, COUNT(r) FROM TestRunEntity r
            WHERE r.status IN :states
            GROUP BY r.teamId
            """)
    List<Object[]> countByTeamForStates(@Param("states") Collection<RunState> states);

    List<TestRunEntity> findByTeamIdOrderByRequestedAtDesc(UUID teamId);
}
