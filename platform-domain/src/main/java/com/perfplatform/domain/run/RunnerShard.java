package com.perfplatform.domain.run;

import com.perfplatform.domain.identity.RunnerShardId;
import com.perfplatform.domain.identity.TestRunId;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * One unit of execution within a run.
 *
 * <p>A run has at least one shard. Sharding exists because a single load generator has a ceiling —
 * CPU, sockets, GC — well below the platform's aggregate target, so large runs are split across
 * autonomous runners. Shard count is chosen by a calibrated capacity profile, never by the consumer.
 *
 * <p>The external handle is what connects this record to something actually executing. It is
 * persisted <em>before</em> the run is allowed to report {@code RUNNING}: a control-plane crash
 * between submitting a runner and recording it would otherwise leave a load generator running that
 * nothing can find or cancel.
 *
 * @param id            shard identifier, used in artifact keys and runner labels
 * @param runId         the run this shard belongs to
 * @param engine        engine identifier, e.g. {@code jmeter}
 * @param workloadName  the workload this shard executes. Two workloads of one run can each have a
 *                      shard 0, so a shard is identified by its workload and index together
 * @param shardIndex    zero-based position, and the value used to select this shard's dataset slice
 * @param shardCount    total shards in the run, so a runner can partition data without asking
 * @param state         current shard state
 * @param handle        the runner provider's handle, absent until provisioned
 * @param lastHeartbeat when the shard last reported; absence past a tolerance means it is lost
 * @param startedAt     when execution began
 * @param completedAt   when execution ended
 * @param exitCode      the runner's exit code, when it produced one
 * @param failureCode   why the shard failed, if it did
 * @param lockVersion   optimistic-lock version
 */
public record RunnerShard(
        RunnerShardId id,
        TestRunId runId,
        String engine,
        String workloadName,
        int shardIndex,
        int shardCount,
        ShardState state,
        Optional<ExecutionHandle> handle,
        Optional<Instant> lastHeartbeat,
        Optional<Instant> startedAt,
        Optional<Instant> completedAt,
        Optional<Integer> exitCode,
        Optional<RunFailureCode> failureCode,
        long lockVersion) {

    /**
     * A runner provider's reference to something it created.
     *
     * <p>Both parts are kept. The name is what an operator greps for and what the provider's API
     * addresses; the uid is what proves the object found later is the same one — a Job name can be
     * reused after deletion, and cancelling the wrong generation would terminate an unrelated run.
     *
     * @param providerId which provider created it, e.g. {@code fake}, {@code kubernetes}
     * @param name       the provider's object name
     * @param uid        the provider's stable unique identifier for this instance
     */
    public record ExecutionHandle(String providerId, String name, String uid) {
        public ExecutionHandle {
            requireText(providerId, "providerId");
            requireText(name, "name");
            requireText(uid, "uid");
        }
    }

    public RunnerShard {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(state, "state");
        requireText(engine, "engine");
        if (shardCount < 1) {
            throw new IllegalArgumentException("shardCount must be at least 1");
        }
        if (shardIndex < 0 || shardIndex >= shardCount) {
            throw new IllegalArgumentException(
                    "shardIndex " + shardIndex + " is outside the range of " + shardCount + " shards");
        }
        handle = orEmpty(handle);
        lastHeartbeat = orEmpty(lastHeartbeat);
        startedAt = orEmpty(startedAt);
        completedAt = orEmpty(completedAt);
        exitCode = orEmpty(exitCode);
        failureCode = orEmpty(failureCode);
    }

    /** A planned shard, not yet submitted anywhere. */
    public static RunnerShard planned(RunnerShardId id, TestRunId runId, String engine,
            String workloadName, int shardIndex, int shardCount) {
        return new RunnerShard(id, runId, engine, workloadName, shardIndex, shardCount, ShardState.PENDING,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), 0L);
    }

    /** Records the provider's handle and moves to {@link ShardState#PROVISIONED}. */
    public RunnerShard provisioned(ExecutionHandle newHandle, Instant at) {
        return new RunnerShard(id, runId, engine, workloadName, shardIndex, shardCount, ShardState.PROVISIONED,
                Optional.of(newHandle), Optional.of(at), startedAt, completedAt,
                exitCode, failureCode, lockVersion);
    }

    /** Marks the shard as executing and stamps its start. */
    public RunnerShard running(Instant at) {
        return new RunnerShard(id, runId, engine, workloadName, shardIndex, shardCount, ShardState.RUNNING,
                handle, Optional.of(at), startedAt.or(() -> Optional.of(at)), completedAt,
                exitCode, failureCode, lockVersion);
    }

    /** Records a heartbeat without otherwise changing the shard. */
    public RunnerShard heartbeat(Instant at) {
        return new RunnerShard(id, runId, engine, workloadName, shardIndex, shardCount, state, handle,
                Optional.of(at), startedAt, completedAt, exitCode, failureCode, lockVersion);
    }

    /** Records a terminal outcome. */
    public RunnerShard finished(ShardState terminal, Integer newExitCode,
            RunFailureCode newFailureCode, Instant at) {
        if (!terminal.isTerminal()) {
            throw new IllegalArgumentException(terminal + " is not a terminal shard state");
        }
        return new RunnerShard(id, runId, engine, workloadName, shardIndex, shardCount, terminal, handle,
                lastHeartbeat, startedAt, Optional.of(at),
                Optional.ofNullable(newExitCode), Optional.ofNullable(newFailureCode), lockVersion);
    }

    /**
     * Whether the shard has gone silent for longer than the tolerance.
     *
     * <p>Only meaningful for a shard that is supposed to be reporting. A pending shard has not
     * started and a terminal one has stopped on purpose; treating either as stale would fail runs
     * that are behaving correctly.
     */
    public boolean isHeartbeatStale(Instant now, Duration tolerance) {
        if (state.isTerminal() || state == ShardState.PENDING) {
            return false;
        }
        return lastHeartbeat
                .map(last -> last.plus(tolerance).isBefore(now))
                .orElse(false);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be null or blank");
        }
    }

    private static <T> Optional<T> orEmpty(Optional<T> value) {
        return value == null ? Optional.empty() : value;
    }
}
