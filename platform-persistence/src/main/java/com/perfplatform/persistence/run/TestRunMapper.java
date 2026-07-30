package com.perfplatform.persistence.run;

import com.perfplatform.domain.identity.ExecutionPlanId;
import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.identity.TestDefinitionVersionId;
import com.perfplatform.domain.identity.TestRunId;
import com.perfplatform.domain.run.TestRun;

import java.util.Optional;

/**
 * Converts between the run row and the run aggregate.
 *
 * <p>The translation exists so neither side has to compromise. The domain record stays immutable
 * with non-null invariants and no framework annotations; the entity keeps the setters and nullable
 * columns JPA needs. Sharing one class would mean giving the domain a no-arg constructor and
 * mutable fields, at which point it can no longer enforce anything about itself.
 */
final class TestRunMapper {

    private TestRunMapper() {
    }

    static TestRun toDomain(TestRunEntity entity) {
        return new TestRun(
                TestRunId.of(entity.getId()),
                TeamId.of(entity.getTeamId()),
                TestDefinitionVersionId.of(entity.getDefinitionVersionId()),
                Optional.ofNullable(entity.getExecutionPlanId()).map(ExecutionPlanId::of),
                entity.getStatus(),
                entity.getEnvironment(),
                new TestRun.TriggerContext(entity.getIdempotencyKey(), entity.getRequestDigest(),
                        entity.getRequestedBy()),
                new TestRun.RunTimeline(
                        entity.getRequestedAt(),
                        Optional.ofNullable(entity.getQueuedAt()),
                        Optional.ofNullable(entity.getStartedAt()),
                        Optional.ofNullable(entity.getCompletedAt())),
                cancellationOf(entity),
                failureOf(entity),
                claimOf(entity),
                entity.isSynthetic(),
                entity.getLockVersion());
    }

    /** A row for a run being created. Only the immutable fields are set; the rest follow. */
    static TestRunEntity newEntity(TestRun run) {
        TestRunEntity entity = new TestRunEntity(
                run.id().value(),
                run.teamId().value(),
                run.definitionVersionId().value(),
                run.state(),
                run.environment(),
                run.trigger().idempotencyKey(),
                run.trigger().requestDigest(),
                run.trigger().requestedBy(),
                run.timeline().requestedAt());
        copyMutableState(run, entity);
        return entity;
    }

    /**
     * Copies the fields a transition can change onto an existing row.
     *
     * <p>Deliberately does not touch identity, team, version, environment or the trigger context.
     * Those are set once at creation; a mapper that could overwrite them would make it possible to
     * repoint a run at a different team or a different specification version by saving it.
     */
    static void copyMutableState(TestRun run, TestRunEntity entity) {
        entity.setStatus(run.state());
        entity.setExecutionPlanId(run.executionPlanId().map(ExecutionPlanId::value).orElse(null));
        entity.setQueuedAt(run.timeline().queuedAt().orElse(null));
        entity.setStartedAt(run.timeline().startedAt().orElse(null));
        entity.setCompletedAt(run.timeline().completedAt().orElse(null));

        run.cancellation().ifPresent(cancellation -> entity.setCancellation(
                cancellation.requestedBy(), cancellation.requestedAt(), cancellation.reason()));

        run.failure().ifPresent(failure ->
                entity.setFailure(failure.code(), failure.sanitizedReason()));

        entity.setClaim(
                run.claim().map(TestRun.Claim::owner).orElse(null),
                run.claim().map(TestRun.Claim::expiresAt).orElse(null));

        // Set, never cleared. The entity offers no way to clear it, so a run read back with the flag
        // already true keeps it even if a caller somehow passed an unmarked aggregate.
        if (run.synthetic()) {
            entity.markSynthetic();
        }
    }

    private static Optional<TestRun.Cancellation> cancellationOf(TestRunEntity entity) {
        if (entity.getCancellationRequestedAt() == null) {
            return Optional.empty();
        }
        return Optional.of(new TestRun.Cancellation(
                entity.getCancellationRequestedBy(),
                entity.getCancellationRequestedAt(),
                entity.getCancellationReason()));
    }

    private static Optional<TestRun.Failure> failureOf(TestRunEntity entity) {
        if (entity.getFailureCode() == null) {
            return Optional.empty();
        }
        return Optional.of(new TestRun.Failure(entity.getFailureCode(), entity.getFailureReason()));
    }

    private static Optional<TestRun.Claim> claimOf(TestRunEntity entity) {
        if (entity.getClaimOwner() == null || entity.getClaimExpiresAt() == null) {
            return Optional.empty();
        }
        return Optional.of(new TestRun.Claim(entity.getClaimOwner(), entity.getClaimExpiresAt()));
    }
}
