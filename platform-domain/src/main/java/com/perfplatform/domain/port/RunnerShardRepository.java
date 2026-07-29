package com.perfplatform.domain.port;

import com.perfplatform.domain.identity.RunnerShardId;
import com.perfplatform.domain.identity.TestRunId;
import com.perfplatform.domain.run.RunnerShard;

import java.util.List;
import java.util.Optional;

/**
 * Durable storage for runner shards.
 *
 * <p>Separate from {@link TestRunRepository} because the access patterns differ: a run is read to
 * answer "what is its state", while shards are read to answer "is anything still executing". Runs
 * are also read far more often than shards, and loading a run's shards on every status query would
 * make the common case pay for the rare one.
 */
public interface RunnerShardRepository {

    Optional<RunnerShard> find(RunnerShardId id);

    /** All shards of a run, ordered by shard index so the sequence is stable in reports. */
    List<RunnerShard> findByRun(TestRunId runId);

    /**
     * Persists a shard.
     *
     * @throws TestRunRepository.OptimisticLockException if the shard changed since it was read
     */
    RunnerShard save(RunnerShard shard);
}
