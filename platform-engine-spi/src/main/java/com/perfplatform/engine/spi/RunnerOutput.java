package com.perfplatform.engine.spi;

import com.perfplatform.domain.identity.TestRunId;

import java.util.List;
import java.util.Objects;

/**
 * What one shard produced, handed to an engine for reduction.
 *
 * <p>Raw result content, not a path and not a stream. The analysis step must be re-runnable from
 * stored artifacts months later, so it takes the content it is given rather than reaching for a
 * file that may since have been moved to object storage, expired, or never existed on this host.
 *
 * @param runId          the run these results claim to belong to
 * @param shardsExpected how many shards the plan called for, so the reducer can report incompleteness
 * @param shardResults   one entry per shard that produced usable output
 */
public record RunnerOutput(TestRunId runId, int shardsExpected, List<ShardResult> shardResults) {

    /**
     * One shard's result content.
     *
     * @param shardIndex which shard
     * @param resultData the result file's content, in whatever format the engine writes
     */
    public record ShardResult(int shardIndex, String resultData) {

        public ShardResult {
            if (shardIndex < 0) {
                throw new IllegalArgumentException("shardIndex must not be negative");
            }
            resultData = resultData == null ? "" : resultData;
        }
    }

    public RunnerOutput {
        Objects.requireNonNull(runId, "runId");
        if (shardsExpected < 1) {
            throw new IllegalArgumentException("shardsExpected must be at least 1");
        }
        shardResults = shardResults == null ? List.of() : List.copyOf(shardResults);
    }
}
