package com.perfplatform.persistence.plan;

import com.perfplatform.domain.identity.ExecutionPlanId;
import com.perfplatform.domain.plan.ExecutionPlan;
import com.perfplatform.domain.port.ExecutionPlanRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Durable storage for compiled execution plans.
 *
 * <p>Insert-only. A plan is content-addressed and referenced by runs whose results were verified
 * against its digest; permitting an update here would let one edit invalidate results that have
 * already been read and acted on, with nothing recording that it happened.
 */
@Repository
public class ExecutionPlanStore implements ExecutionPlanRepository {

    private final ExecutionPlanJpaRepository plans;

    public ExecutionPlanStore(ExecutionPlanJpaRepository plans) {
        this.plans = plans;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ExecutionPlan> find(ExecutionPlanId id) {
        return plans.findById(id.value()).map(ExecutionPlanMapper::toDomain);
    }

    @Override
    @Transactional
    public ExecutionPlan save(ExecutionPlan plan) {
        if (plans.existsById(plan.id().value())) {
            throw new ImmutablePlanException(plan.id());
        }
        return ExecutionPlanMapper.toDomain(plans.saveAndFlush(ExecutionPlanMapper.toEntity(plan)));
    }
}
