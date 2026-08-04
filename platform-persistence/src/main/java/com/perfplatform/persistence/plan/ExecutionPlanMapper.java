package com.perfplatform.persistence.plan;

import com.perfplatform.domain.identity.ExecutionPlanId;
import com.perfplatform.domain.identity.TestDefinitionVersionId;
import com.perfplatform.domain.plan.ExecutionPlan;
import com.perfplatform.domain.spec.SpecificationDigest;

/** Converts between the plan row and the plan record. */
final class ExecutionPlanMapper {

    private ExecutionPlanMapper() {
    }

    static ExecutionPlan toDomain(ExecutionPlanEntity entity) {
        return new ExecutionPlan(
                ExecutionPlanId.of(entity.getId()),
                TestDefinitionVersionId.of(entity.getDefinitionVersionId()),
                entity.getWorkloadName(),
                entity.getEngine(),
                entity.getPlanJson(),
                SpecificationDigest.parse(entity.getPlanDigest()),
                new ExecutionPlan.CapacityEstimate(
                        entity.getShardCount(),
                        entity.getPeakVirtualUsers(),
                        entity.getEstimatedDurationSeconds()),
                entity.getCreatedAt());
    }

    static ExecutionPlanEntity toEntity(ExecutionPlan plan, java.util.UUID runId) {
        return new ExecutionPlanEntity(
                plan.id().value(),
                plan.definitionVersionId().value(),
                plan.workloadName(),
                runId,
                plan.engine(),
                plan.planJson(),
                plan.planDigest().value(),
                plan.capacity().shardCount(),
                plan.capacity().peakVirtualUsers(),
                plan.capacity().estimatedDurationSeconds(),
                plan.createdAt());
    }
}
