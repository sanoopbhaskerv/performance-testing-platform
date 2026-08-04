package com.perfplatform.orchestration.run;

import com.perfplatform.domain.dataset.DatasetPartitioner;
import com.perfplatform.domain.identity.ExecutionPlanId;
import com.perfplatform.domain.identity.RunnerShardId;
import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.identity.TestDefinitionVersionId;
import com.perfplatform.domain.identity.TestRunId;
import com.perfplatform.domain.plan.ExecutionPlan;
import com.perfplatform.domain.port.ArtifactStore.ArtifactReference;
import com.perfplatform.domain.port.ArtifactStore.ArtifactType;
import com.perfplatform.domain.port.DefinitionVersionCatalog.DefinitionVersion;
import com.perfplatform.domain.run.RunnerShard;
import com.perfplatform.domain.run.TestRun;
import com.perfplatform.domain.spec.PerformanceTestSpecification;
import com.perfplatform.domain.spec.PerformanceTestSpecification.DatasetSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.TestSpec;
import com.perfplatform.domain.spec.SpecificationDigest;
import com.perfplatform.orchestration.plan.EngineRegistry;
import com.perfplatform.orchestration.support.OrchestrationFixtures;
import com.perfplatform.orchestration.support.OrchestrationFixtures.InMemoryArtifacts;
import com.perfplatform.orchestration.support.OrchestrationFixtures.StubEngineProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Getting a run's data to the shard that will send it.
 *
 * <p>The recurring failure this guards against: an unresolved {@code ${orderId}} is not sent as an
 * empty value, it is sent as those literal characters. The target answers 404 to every request in a
 * millisecond or two, and the run reports excellent latency for a system that did no work. So every
 * problem here is refused <em>before</em> a runner is asked to start, never surfaced afterwards as a
 * curiously fast result.
 */
class RunWorkloadDatasetTest {

    private static final Instant NOW = Instant.parse("2026-08-02T09:00:00Z");
    private static final String CSV = """
            orderId,customerId
            O-1,C-1
            O-2,C-2
            O-3,C-3
            O-4,C-4
            O-5,C-5
            """;

    private final TeamId team = TeamId.generate();
    private final InMemoryArtifacts artifacts = new InMemoryArtifacts();
    private final OrchestrationFixtures.InMemoryDatasets datasets =
            new OrchestrationFixtures.InMemoryDatasets();

    private final RunWorkloadService workloads = new RunWorkloadService(
            new EngineRegistry(List.of(new StubEngineProvider())), artifacts, artifacts,
            OrchestrationFixtures.permissiveAnalyzer(), OrchestrationFixtures.fixedTarget(),
            datasets, datasets, new RuntimeCredentialSessionPoolProvider(),
            new OrchestrationFixtures.InMemoryProgress());

    private final TestRunId runId = TestRunId.generate();
    private final TestDefinitionVersionId versionId = TestDefinitionVersionId.generate();

    @Nested
    @DisplayName("delivery")
    class Delivery {

        @Test
        @DisplayName("a declared dataset becomes a stored partition and a delivery for the shard")
        void deliversAPartition() {
            datasets.given(team, "proposal-clients", 1, CSV);

            var prepared = prepare(specificationWith(dataset(
                    "dataset://proposal-clients/1", List.of("orderId"), true)), 0, 1);

            assertThat(prepared.datasets()).hasSize(1);
            assertThat(prepared.datasets().get(0).fileName()).isEqualTo("client-data.csv");
            assertThat(contentOf(prepared.datasets().get(0).objectKey())).isEqualTo(CSV.strip());
        }

        /**
         * A dataset partition is a CSV and so is a JMeter results file. Without a distinguishing
         * name the input would be adopted as a result and its rows parsed as measurements — which
         * could change a verdict with nothing looking wrong.
         */
        @Test
        @DisplayName("the partition is stored as DATASET, under a name no result can collide with")
        void storedAsItsOwnType() {
            datasets.given(team, "proposal-clients", 1, CSV);

            var prepared = prepare(specificationWith(dataset(
                    "dataset://proposal-clients/1", List.of("orderId"), true)), 0, 1);

            ArtifactReference stored = artifacts.artifactsFor(runId).stream()
                    .filter(reference -> reference.type() == ArtifactType.DATASET)
                    .findFirst()
                    .orElseThrow();

            assertThat(stored.objectKey()).endsWith("dataset-client-data.csv");
            assertThat(prepared.datasets().get(0).objectKey()).isEqualTo(stored.objectKey());
        }

        /**
         * The point of partitioning: ten generators reading one file all submit the same order,
         * against the same account, in the same sequence. The target's caches answer most of it and
         * the write path is exercised once while appearing to be exercised ten times.
         */
        @Test
        @DisplayName("two shards receive non-overlapping rows, and both keep the header")
        void shardsDoNotOverlap() {
            datasets.given(team, "proposal-clients", 1, CSV);
            var spec = specificationWith(
                    dataset("dataset://proposal-clients/1", List.of("orderId"), true));

            String first = contentOf(prepare(spec, 0, 2).datasets().get(0).objectKey());
            String second = contentOf(prepare(spec, 1, 2).datasets().get(0).objectKey());

            assertThat(rowsOf(first)).containsExactly("O-1,C-1", "O-3,C-3", "O-5,C-5");
            assertThat(rowsOf(second)).containsExactly("O-2,C-2", "O-4,C-4");
            assertThat(first).startsWith("orderId,customerId");
            assertThat(second).startsWith("orderId,customerId");
        }

        /**
         * What the specification asked for, and what the validator already warns about: with more
         * than one shard, every generator replays the same rows.
         */
        @Test
        @DisplayName("a dataset not partitioned by shard is delivered whole to each shard")
        void unpartitionedIsDeliveredWhole() {
            datasets.given(team, "proposal-clients", 1, CSV);
            var spec = specificationWith(
                    dataset("dataset://proposal-clients/1", List.of("orderId"), false));

            assertThat(rowsOf(contentOf(prepare(spec, 1, 2).datasets().get(0).objectKey())))
                    .hasSize(5);
        }

        @Test
        @DisplayName("a test with no dataset prepares no deliveries")
        void noDatasets() {
            assertThat(prepare(OrchestrationFixtures.apiSpecification(), 0, 1).datasets()).isEmpty();
        }
    }

    @Nested
    @DisplayName("refusals, all before any load is generated")
    class Refusals {

        @Test
        @DisplayName("a dataset the team has not uploaded fails the run")
        void unregistered() {
            assertThatThrownBy(() -> prepare(specificationWith(
                    dataset("dataset://proposal-clients/1", List.of("orderId"), true)), 0, 1))
                    .isInstanceOf(RunWorkloadService.DatasetUnavailableException.class)
                    .hasMessageContaining("dataset://proposal-clients/1");
        }

        /**
         * The check that turns {@code columns} from a second source of truth into a contract. A
         * dataset re-exported with {@code order_id} where the test expects {@code orderId} passes
         * validation — validation ran against the declaration, before any file existed.
         */
        @Test
        @DisplayName("a file missing a declared column fails the run, naming what is missing")
        void columnMismatch() {
            datasets.given(team, "proposal-clients", 1, "order_id,customerId\nO-1,C-1");

            assertThatThrownBy(() -> prepare(specificationWith(
                    dataset("dataset://proposal-clients/1", List.of("orderId"), true)), 0, 1))
                    .isInstanceOf(RunWorkloadService.DatasetUnavailableException.class)
                    .hasMessageContaining("orderId");
        }

        @Test
        @DisplayName("extra columns in the file are fine: a dataset may serve more than one test")
        void extraColumnsAreAccepted() {
            datasets.given(team, "proposal-clients", 1, CSV);

            assertThat(prepare(specificationWith(
                    dataset("dataset://proposal-clients/1", List.of("orderId"), true)), 0, 1)
                    .datasets()).hasSize(1);
        }

        @Test
        @DisplayName("a source uri that is not a dataset reference fails the run")
        void unparseableUri() {
            assertThatThrownBy(() -> prepare(specificationWith(
                    dataset("s3://bucket/clients.csv", List.of(), true)), 0, 1))
                    .isInstanceOf(RunWorkloadService.DatasetUnavailableException.class);
        }

        /** A team's own prefix is the only place looked, so a name cannot reach another team's data. */
        @Test
        @DisplayName("another team's dataset of the same name does not resolve")
        void otherTeamsDatasetIsInvisible() {
            datasets.given(TeamId.generate(), "proposal-clients", 1, CSV);

            assertThatThrownBy(() -> prepare(specificationWith(
                    dataset("dataset://proposal-clients/1", List.of("orderId"), true)), 0, 1))
                    .isInstanceOf(RunWorkloadService.DatasetUnavailableException.class);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private RunWorkloadService.PreparedShard prepare(PerformanceTestSpecification specification,
            int shardIndex, int shardCount) {

        DefinitionVersion version = new DefinitionVersion(versionId,
                com.perfplatform.domain.identity.TestDefinitionId.generate(), team, 1,
                SpecificationDigest.of(specification), specification, true);

        ExecutionPlan plan = new ExecutionPlan(ExecutionPlanId.generate(), versionId,
                "api-load", StubEngineProvider.ENGINE_ID, "{\"stub\":true}",
                SpecificationDigest.ofCanonicalForm("plan"),
                new ExecutionPlan.CapacityEstimate(shardCount, 100, 900), NOW);

        TestRun run = TestRun.request(runId, team, versionId, "performance",
                new TestRun.TriggerContext("key", plan.planDigest().value(), "alice@example.com"),
                NOW).run();

        RunnerShard shard = RunnerShard.planned(RunnerShardId.generate(), runId,
                StubEngineProvider.ENGINE_ID, "api-load", shardIndex, shardCount);

        return workloads.prepareShard(run, plan, version, shard);
    }

    private static DatasetSpec dataset(String uri, List<String> columns, boolean partition) {
        return new DatasetSpec("client-data", new DatasetSpec.DataSource("object-storage", uri),
                columns, partition);
    }

    private static PerformanceTestSpecification specificationWith(DatasetSpec dataset) {
        PerformanceTestSpecification base = OrchestrationFixtures.apiSpecification();
        var body = base.spec();
        return new PerformanceTestSpecification(base.apiVersion(), base.kind(), base.metadata(),
                new TestSpec(body.environment(), body.sources(), body.authentication(),
                        List.of(dataset), body.workloads(), body.objectives(),
                        body.observability(), body.governance()));
    }

    private String contentOf(String objectKey) {
        return artifacts.artifactsFor(runId).stream()
                .filter(reference -> reference.objectKey().equals(objectKey))
                .findFirst()
                .flatMap(artifacts::read)
                .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                .orElseThrow(() -> new AssertionError("Nothing stored under " + objectKey));
    }

    private static List<String> rowsOf(String csv) {
        return DatasetPartitioner.partition(csv, 1).get(0).rows();
    }
}
