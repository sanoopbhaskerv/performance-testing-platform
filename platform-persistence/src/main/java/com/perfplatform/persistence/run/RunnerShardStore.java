package com.perfplatform.persistence.run;

import com.perfplatform.domain.identity.RunnerShardId;
import com.perfplatform.domain.identity.TestRunId;
import com.perfplatform.domain.port.RunnerShardRepository;
import com.perfplatform.domain.port.TestRunRepository;
import com.perfplatform.domain.run.RunnerShard;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Durable storage for runner shards.
 *
 * <p>{@link #save} handles both insert and update because a shard's identity is assigned before it
 * is first stored, so there is no meaningful distinction from the caller's side — the reconciler
 * plans a shard, stores it, then repeatedly updates it as the provider reports progress.
 */
@Repository
public class RunnerShardStore implements RunnerShardRepository {

    private final RunnerShardJpaRepository shards;

    public RunnerShardStore(RunnerShardJpaRepository shards) {
        this.shards = shards;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RunnerShard> find(RunnerShardId id) {
        return shards.findById(id.value()).map(RunnerShardMapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RunnerShard> findByRun(TestRunId runId) {
        return shards.findByRunIdOrderByShardIndexAsc(runId.value()).stream()
                .map(RunnerShardMapper::toDomain)
                .toList();
    }

    @Override
    @Transactional
    public RunnerShard save(RunnerShard shard) {
        try {
            RunnerShardEntity entity = shards.findById(shard.id().value())
                    .map(existing -> {
                        RunnerShardMapper.copyMutableState(shard, existing);
                        return existing;
                    })
                    .orElseGet(() -> RunnerShardMapper.newEntity(shard));

            return RunnerShardMapper.toDomain(shards.saveAndFlush(entity));
        } catch (ObjectOptimisticLockingFailureException e) {
            // Two reconcilers observed the same shard. Surfaced rather than retried here: the
            // caller re-reads on the next pass, and a silent retry would write a stale observation
            // over a fresher one.
            throw new TestRunRepository.OptimisticLockException(shard.runId());
        }
    }
}
