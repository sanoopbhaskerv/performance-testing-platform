package com.perfplatform.persistence.plan;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Spring Data access to execution plan rows. */
public interface ExecutionPlanJpaRepository extends JpaRepository<ExecutionPlanEntity, UUID> {

    /** Plans sharing a digest. Two runs of an unchanged test compile to the same plan. */
    List<ExecutionPlanEntity> findByPlanDigest(String planDigest);

    /**
     * A run's plans, ordered so two reads list them identically.
     *
     * <p>Ordered in the query rather than by the caller: a report whose workloads reshuffled between
     * refreshes would be unreadable, and row order is not a promise the database makes.
     */
    List<ExecutionPlanEntity> findByRunIdOrderByWorkloadNameAsc(UUID runId);
}
