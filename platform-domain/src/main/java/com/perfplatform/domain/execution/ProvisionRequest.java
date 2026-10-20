package com.perfplatform.domain.execution;

import com.perfplatform.domain.identity.ExecutionPlanId;
import com.perfplatform.domain.identity.RunnerShardId;
import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.identity.TestRunId;
import com.perfplatform.domain.spec.SpecificationDigest;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Everything a runner provider needs to start one shard.
 *
 * <h2>What is deliberately absent</h2>
 * No persisted secrets and no specification-level credential values. S5 carries run-scoped
 * runtime credentials only from trigger to provisioning. A provider must deliver those to the
 * runner without embedding the values in labels, annotations, command arguments or stored plans;
 * Kubernetes does that through per-shard Secret references and mounted Secret files, and local
 * development does it through child-process environment plus local scratch files only.
 *
 * <p>Also absent: the specification. The runner executes the compiled plan, which it fetches by
 * identifier. Passing the specification would invite a runner to reinterpret it and produce
 * something other than what was approved.
 *
 * @param runId       the run, also the correlation value injected into workload traffic so that
 *                    application-side observability can attribute the load
 * @param teamId      the owning team, used for artifact key prefixes and provider-side labelling
 * @param shardId     the shard being started
 * @param shardIndex  zero-based index, which selects this shard's dataset slice
 * @param shardCount  total shards, so the runner can partition data without a second call
 * @param engine      engine identifier, which selects the runner image
 * @param planId      the compiled plan to execute
 * @param planDigest  expected plan digest; the runner echoes it back and results that disagree are
 *                    rejected rather than merged
 * @param workloadKey artifact key of the rendered workload this shard executes. A key, not a path:
 *                    the control plane and the runner do not share a filesystem, and a path would be
 *                    a promise about one that does not exist
 * @param operationLabels the labels this shard's results will be reported under, as
 *                    {@code workload/scenario}. Carried so a runner can label its output the way the
 *                    platform expects to read it — objectives are scoped by workload and by
 *                    scenario, and results that used different names could not be judged against them
 * @param datasets    this shard's dataset partitions, as keys plus the file names the engine expects
 *                    them under. Already sliced so no two shards send the same row — a provider
 *                    fetches and places them, and decides nothing about their contents
 * @param secretFiles run-scoped secret files. Never persisted as artifacts and never logged.
 * @param runtimeCredentials run-scoped scalar credential material. Never persisted and never logged.
 * @param deadline    hard wall-clock ceiling; the provider must stop the workload at this point
 *                    even if the plan believes it has more to do
 */
public record ProvisionRequest(
        TestRunId runId,
        TeamId teamId,
        RunnerShardId shardId,
        int shardIndex,
        int shardCount,
        String engine,
        ExecutionPlanId planId,
        SpecificationDigest planDigest,
        String workloadKey,
        List<String> operationLabels,
        List<DatasetDelivery> datasets,
        List<SecretFileDelivery> secretFiles,
        List<RuntimeCredential> runtimeCredentials,
        Duration deadline) {

    public ProvisionRequest {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(shardId, "shardId");
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(planDigest, "planDigest");
        Objects.requireNonNull(deadline, "deadline");
        if (workloadKey == null || workloadKey.isBlank()) {
            throw new IllegalArgumentException("workloadKey must not be null or blank");
        }
        operationLabels = operationLabels == null ? List.of() : List.copyOf(operationLabels);
        datasets = datasets == null ? List.of() : List.copyOf(datasets);
        secretFiles = secretFiles == null ? List.of() : List.copyOf(secretFiles);
        runtimeCredentials = runtimeCredentials == null ? List.of() : List.copyOf(runtimeCredentials);
        if (engine == null || engine.isBlank()) {
            throw new IllegalArgumentException("engine must not be null or blank");
        }
        if (shardCount < 1) {
            throw new IllegalArgumentException("shardCount must be at least 1");
        }
        if (shardIndex < 0 || shardIndex >= shardCount) {
            throw new IllegalArgumentException(
                    "shardIndex " + shardIndex + " is outside the range of " + shardCount + " shards");
        }
        if (deadline.isNegative() || deadline.isZero()) {
            throw new IllegalArgumentException("deadline must be positive");
        }
    }

    /** The shape that predates runtime credentials. */
    public ProvisionRequest(TestRunId runId, TeamId teamId, RunnerShardId shardId,
            int shardIndex, int shardCount, String engine, ExecutionPlanId planId,
            SpecificationDigest planDigest, String workloadKey, List<String> operationLabels,
            List<DatasetDelivery> datasets, Duration deadline) {
        this(runId, teamId, shardId, shardIndex, shardCount, engine, planId, planDigest,
                workloadKey, operationLabels, datasets, List.of(), List.of(), deadline);
    }

    /** The shape that predates secret file delivery. */
    public ProvisionRequest(TestRunId runId, TeamId teamId, RunnerShardId shardId,
            int shardIndex, int shardCount, String engine, ExecutionPlanId planId,
            SpecificationDigest planDigest, String workloadKey, List<String> operationLabels,
            List<DatasetDelivery> datasets, List<RuntimeCredential> runtimeCredentials,
            Duration deadline) {
        this(runId, teamId, shardId, shardIndex, shardCount, engine, planId, planDigest,
                workloadKey, operationLabels, datasets, List.of(), runtimeCredentials, deadline);
    }
}
