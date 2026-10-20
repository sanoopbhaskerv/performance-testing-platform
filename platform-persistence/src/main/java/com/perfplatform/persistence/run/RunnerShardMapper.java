package com.perfplatform.persistence.run;

import com.perfplatform.domain.identity.RunnerShardId;
import com.perfplatform.domain.identity.TestRunId;
import com.perfplatform.domain.run.RunnerShard;

import java.util.Optional;

/** Converts between the shard row and the shard record. */
final class RunnerShardMapper {

    private RunnerShardMapper() {
    }

    static RunnerShard toDomain(RunnerShardEntity entity) {
        return new RunnerShard(
                RunnerShardId.of(entity.getId()),
                TestRunId.of(entity.getRunId()),
                entity.getEngine(),
                entity.getWorkloadName(),
                entity.getShardIndex(),
                entity.getShardCount(),
                entity.getStatus(),
                handleOf(entity),
                Optional.ofNullable(entity.getLastHeartbeatAt()),
                Optional.ofNullable(entity.getStartedAt()),
                Optional.ofNullable(entity.getCompletedAt()),
                Optional.ofNullable(entity.getExitCode()),
                Optional.ofNullable(entity.getFailureCode()),
                entity.getLockVersion());
    }

    static RunnerShardEntity newEntity(RunnerShard shard) {
        RunnerShardEntity entity = new RunnerShardEntity(
                shard.id().value(),
                shard.runId().value(),
                shard.engine(),
                shard.workloadName(),
                shard.shardIndex(),
                shard.shardCount(),
                shard.state());
        copyMutableState(shard, entity);
        return entity;
    }

    static void copyMutableState(RunnerShard shard, RunnerShardEntity entity) {
        entity.setStatus(shard.state());
        entity.setLastHeartbeatAt(shard.lastHeartbeat().orElse(null));
        entity.setStartedAt(shard.startedAt().orElse(null));
        entity.setCompletedAt(shard.completedAt().orElse(null));
        entity.setExitCode(shard.exitCode().orElse(null));
        entity.setFailureCode(shard.failureCode().orElse(null));

        shard.handle().ifPresent(handle ->
                entity.setHandle(handle.providerId(), handle.name(), handle.uid()));
    }

    private static Optional<RunnerShard.ExecutionHandle> handleOf(RunnerShardEntity entity) {
        if (entity.getExternalUid() == null || entity.getProviderId() == null
                || entity.getExternalName() == null) {
            return Optional.empty();
        }
        return Optional.of(new RunnerShard.ExecutionHandle(
                entity.getProviderId(), entity.getExternalName(), entity.getExternalUid()));
    }
}
