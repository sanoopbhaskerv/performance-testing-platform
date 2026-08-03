package com.perfplatform.runner.kubernetes;

import com.perfplatform.domain.execution.ProvisionRequest;
import com.perfplatform.domain.execution.RuntimeCredential;
import com.perfplatform.domain.execution.SecretFileDelivery;
import com.perfplatform.domain.identity.ExecutionPlanId;
import com.perfplatform.domain.identity.RunnerShardId;
import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.identity.TestRunId;
import com.perfplatform.domain.spec.SpecificationDigest;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Shared inputs for the runner-cell tests. */
final class RunnerFixtures {

    static final Duration DEADLINE = Duration.ofMinutes(20);

    static final RunnerCellSettings SETTINGS = new RunnerCellSettings(
            "perf-runners",
            "perf-runner",
            Map.of(
                    "jmeter", "registry.internal/perfplatform/jmeter-runner:5.6.3-1",
                    "playwright", "registry.internal/perfplatform/playwright-runner:1.49.1-1"),
            "2", "4",
            "2Gi", "4Gi",
            "8Gi",
            Duration.ofHours(6),
            Duration.ofMinutes(5),
            RunnerCellSettings.ArtifactStorage.usingPodIdentity("perf-artifacts", "us-east-1", "", false));

    private RunnerFixtures() {
    }

    static RunnerCellSettings settingsWithStorage(RunnerCellSettings.ArtifactStorage storage) {
        return new RunnerCellSettings(
                SETTINGS.namespace(), SETTINGS.serviceAccount(), SETTINGS.runnerImages(),
                SETTINGS.cpuRequest(), SETTINGS.cpuLimit(),
                SETTINGS.memoryRequest(), SETTINGS.memoryLimit(),
                SETTINGS.workspaceSizeLimit(), SETTINGS.finishedJobTtl(), SETTINGS.deadlineGrace(),
                storage);
    }

    static RunnerCellSettings settingsWithImages(Map<String, String> images) {
        return new RunnerCellSettings(
                SETTINGS.namespace(), SETTINGS.serviceAccount(), images,
                SETTINGS.cpuRequest(), SETTINGS.cpuLimit(),
                SETTINGS.memoryRequest(), SETTINGS.memoryLimit(),
                SETTINGS.workspaceSizeLimit(), SETTINGS.finishedJobTtl(), SETTINGS.deadlineGrace(),
                SETTINGS.artifacts());
    }

    static ProvisionRequest request() {
        return requestForShard(0, 1);
    }

    static ProvisionRequest requestForShard(int shardIndex, int shardCount) {
        return build(shardIndex, shardCount, "jmeter");
    }

    static ProvisionRequest requestForEngine(String engine) {
        return build(0, 1, engine);
    }

    private static ProvisionRequest build(int shardIndex, int shardCount, String engine) {
        return build(shardIndex, shardCount, engine, List.of());
    }

    /** A request carrying dataset partitions, for the delivery half of the Job contract. */
    static ProvisionRequest requestWithDatasets(
            List<com.perfplatform.domain.execution.DatasetDelivery> datasets) {
        return build(0, 1, "jmeter", datasets, List.of());
    }

    static ProvisionRequest requestWithCredentials(List<RuntimeCredential> credentials) {
        return build(0, 1, "jmeter", List.of(), credentials);
    }

    static ProvisionRequest requestWithSecretFiles(List<SecretFileDelivery> secretFiles) {
        return build(0, 1, "jmeter", List.of(), secretFiles, List.of());
    }

    static SecretFileDelivery sessionFile(String content) {
        return new SecretFileDelivery("sessions.csv",
                content.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                Instant.parse("2026-08-03T12:00:00Z"),
                Instant.parse("2026-08-03T11:00:00Z"));
    }

    private static ProvisionRequest build(int shardIndex, int shardCount, String engine,
            List<com.perfplatform.domain.execution.DatasetDelivery> datasets) {
        return build(shardIndex, shardCount, engine, datasets, List.of());
    }

    private static ProvisionRequest build(int shardIndex, int shardCount, String engine,
            List<com.perfplatform.domain.execution.DatasetDelivery> datasets,
            List<RuntimeCredential> credentials) {
        return build(shardIndex, shardCount, engine, datasets, List.of(), credentials);
    }

    private static ProvisionRequest build(int shardIndex, int shardCount, String engine,
            List<com.perfplatform.domain.execution.DatasetDelivery> datasets,
            List<SecretFileDelivery> secretFiles,
            List<RuntimeCredential> credentials) {
        TestRunId runId = TestRunId.generate();
        return new ProvisionRequest(
                runId,
                TeamId.generate(),
                RunnerShardId.generate(),
                shardIndex,
                shardCount,
                engine,
                ExecutionPlanId.generate(),
                SpecificationDigest.ofCanonicalForm("plan-" + runId),
                "teams/t/runs/" + runId.value() + "/shards/s/shard-0.jmx",
                List.of("checkout-load/browse"),
                datasets,
                secretFiles,
                credentials,
                DEADLINE);
    }
}
