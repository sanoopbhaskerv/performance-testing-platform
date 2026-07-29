package com.perfplatform.persistence.plan;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Spring Data access to execution plan rows. */
public interface ExecutionPlanJpaRepository extends JpaRepository<ExecutionPlanEntity, UUID> {

    /** Plans sharing a digest. Two runs of an unchanged test compile to the same plan. */
    List<ExecutionPlanEntity> findByPlanDigest(String planDigest);
}
