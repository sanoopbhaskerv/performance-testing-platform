package com.perfplatform.persistence.artifact;

import com.perfplatform.domain.identity.ArtifactId;
import com.perfplatform.domain.identity.RunnerShardId;
import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.identity.TestRunId;
import com.perfplatform.domain.port.ArtifactStore.ArtifactDescriptor;
import com.perfplatform.domain.port.ArtifactStore.ArtifactReference;
import com.perfplatform.domain.port.ArtifactStore.ArtifactType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The S3 artifact store against a real S3-compatible server.
 *
 * <p>MinIO rather than a mock. The properties worth testing here — that the key layout puts the team
 * first, that a digest survives the round trip, that a missing key is empty rather than an error —
 * are precisely the ones an in-memory fake would be written to satisfy, which would make the test
 * agree with itself rather than with S3.
 *
 * <p>This is the store that makes a multi-replica control plane possible. The filesystem store
 * cannot: a file written by one replica is invisible to the others, and a runner Pod on another
 * node cannot reach it at all.
 */
@Testcontainers(disabledWithoutDocker = true)
class S3ArtifactStoreMinioTest {

    private static final String BUCKET = "perf-artifacts";
    private static final Instant NOW = Instant.parse("2026-07-30T10:15:30Z");

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> MINIO = new GenericContainer<>(
            new ImageFromDockerfile("perfplatform/minio-test:2024-11-07-1", false)
                    .withFileFromClasspath("Dockerfile", "minio/Dockerfile"))
            .withEnv("MINIO_ROOT_USER", "minioadmin")
            .withEnv("MINIO_ROOT_PASSWORD", "minioadmin")
            .withExposedPorts(9000)
            .withCommand("server", "/data")
            .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000));

    private static S3Client s3;
    private static S3ArtifactStore store;

    @BeforeAll
    static void createBucket() {
        s3 = S3Client.builder()
                .endpointOverride(URI.create("http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000)))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("minioadmin", "minioadmin")))
                .region(Region.US_EAST_1)
                // MinIO addresses buckets by path, not by virtual host. The same setting is what a
                // deployment behind an S3-compatible appliance needs, so it is configuration in
                // production rather than something this test alone knows about.
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();

        s3.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        store = new S3ArtifactStore(s3, BUCKET, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static ArtifactDescriptor descriptor(TeamId teamId, TestRunId runId,
            Optional<RunnerShardId> shardId, ArtifactType type, String fileName, String contentType) {
        return new ArtifactDescriptor(teamId, runId, shardId, type, fileName, contentType);
    }

    @Test
    @DisplayName("content stored is content read back, byte for byte")
    void roundTripsContent() {
        byte[] content = "timeStamp,elapsed,label\n1,2,browse\n".getBytes(StandardCharsets.UTF_8);

        ArtifactReference stored = store.store(descriptor(TeamId.generate(), TestRunId.generate(),
                Optional.of(RunnerShardId.generate()), ArtifactType.RESULTS,
                "results.jtl", "text/csv"), content);

        assertThat(store.read(stored)).hasValueSatisfying(read ->
                assertThat(read).isEqualTo(content));
        assertThat(stored.sizeBytes()).isEqualTo(content.length);
        assertThat(stored.contentType()).isEqualTo("text/csv");
        assertThat(stored.storedAt()).isEqualTo(NOW);
    }

    /**
     * The key layout is what makes authorization structural: a caller who may not read a team's data
     * cannot construct a key inside it. It is identical to the filesystem store's, which is what
     * keeps the two interchangeable.
     */
    @Test
    @DisplayName("the key puts the team first, then the run, then the shard")
    void keyLayoutIsTeamPrefixed() {
        TeamId teamId = TeamId.generate();
        TestRunId runId = TestRunId.generate();
        RunnerShardId shardId = RunnerShardId.generate();

        ArtifactReference stored = store.store(descriptor(teamId, runId, Optional.of(shardId),
                ArtifactType.RESULTS, "results.jtl", "text/csv"), new byte[] {1});

        assertThat(stored.objectKey()).isEqualTo(
                "teams/%s/runs/%s/shards/%s/results.jtl"
                        .formatted(teamId.value(), runId.value(), shardId.value()));
    }

    /**
     * The digest recorded at upload is what a download is later verified against, so it has to be a
     * digest of the content rather than of anything else.
     */
    @Test
    @DisplayName("the recorded digest is the SHA-256 of the content that was stored")
    void digestIsOfTheContent() throws Exception {
        byte[] content = "evidence".getBytes(StandardCharsets.UTF_8);

        ArtifactReference stored = store.store(descriptor(TeamId.generate(), TestRunId.generate(),
                Optional.empty(), ArtifactType.RUNNER_MANIFEST,
                "runner-manifest.json", "application/json"), content);

        String expected = "sha256:" + java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(content));

        assertThat(stored.digest()).isEqualTo(expected);
    }

    /**
     * Retention reclaims artifacts, so a report asking for one that has aged out is an ordinary
     * situation. Empty lets the caller say so honestly; an exception would make it an incident.
     */
    @Test
    @DisplayName("reading an object that is gone is empty, not an error")
    void missingObjectIsEmpty() {
        ArtifactReference missing = new ArtifactReference(
                ArtifactId.generate(), TestRunId.generate(), Optional.empty(),
                ArtifactType.RESULTS, "teams/none/runs/none/analysis/results.jtl",
                "sha256:none", "text/csv", 0, NOW);

        assertThat(store.read(missing)).isEmpty();
    }

    /**
     * A repeated reconciliation pass re-stores the same content under the same key. It must not fail,
     * and it must not leave two objects behind.
     */
    @Test
    @DisplayName("storing the same key twice overwrites rather than failing")
    void storeIsIdempotentByKey() {
        TeamId teamId = TeamId.generate();
        TestRunId runId = TestRunId.generate();
        ArtifactDescriptor descriptor = descriptor(teamId, runId, Optional.empty(),
                ArtifactType.EXECUTION_PLAN, "shard-0.jmx", "application/xml");

        store.store(descriptor, "first".getBytes(StandardCharsets.UTF_8));
        ArtifactReference second = store.store(descriptor, "second".getBytes(StandardCharsets.UTF_8));

        assertThat(store.read(second)).hasValueSatisfying(read ->
                assertThat(new String(read, StandardCharsets.UTF_8)).isEqualTo("second"));
        assertThat(store.listForRun(runId)).hasSize(1);
    }

    @Test
    @DisplayName("deleting an artifact removes the object and is safe to repeat")
    void deleteRemovesTheObject() {
        ArtifactReference stored = store.store(descriptor(TeamId.generate(), TestRunId.generate(),
                Optional.empty(), ArtifactType.RUNNER_LOG, "jmeter.log", "text/plain"),
                "started\n".getBytes(StandardCharsets.UTF_8));

        store.delete(stored);
        store.delete(stored);

        assertThat(store.read(stored)).isEmpty();
    }

    @Test
    @DisplayName("a run's artifacts can be listed back from storage alone")
    void listsARunsArtifacts() {
        TeamId teamId = TeamId.generate();
        TestRunId runId = TestRunId.generate();

        store.store(descriptor(teamId, runId, Optional.empty(), ArtifactType.EXECUTION_PLAN,
                "shard-0.jmx", "application/xml"), new byte[] {1});
        store.store(descriptor(teamId, runId, Optional.empty(), ArtifactType.RESULTS,
                "results.jtl", "text/csv"), new byte[] {2, 3});

        assertThat(store.listForRun(runId))
                .extracting(ArtifactReference::type)
                .containsExactlyInAnyOrder(ArtifactType.EXECUTION_PLAN, ArtifactType.RESULTS);
    }

    /**
     * The case a real cluster run exposed and no earlier test did.
     *
     * <p>A runner uploads with an ordinary S3 client and sets none of this platform's object
     * metadata — it shares no process, filesystem or database with the control plane. An
     * implementation that identified artifacts by metadata silently ignored everything a real runner
     * produced, and the run reported "0 of 1 shards reported" rather than any error at all.
     *
     * <p>The key layout is the contract, so identity comes from the key.
     */
    @Test
    @DisplayName("an object uploaded without platform metadata is still recognised")
    void adoptsUploadsThatCarryNoMetadata() {
        TeamId teamId = TeamId.generate();
        TestRunId runId = TestRunId.generate();
        RunnerShardId shardId = RunnerShardId.generate();
        String prefix = "teams/%s/runs/%s/shards/%s/"
                .formatted(teamId.value(), runId.value(), shardId.value());

        // Exactly what the runner image writes, by the same route: a plain PutObject with no
        // x-amz-meta-perf-* headers at all.
        putRaw(prefix + "results.jtl", "timeStamp,elapsed,label\n1,2,browse\n");
        putRaw(prefix + "jmeter.log", "started\n");
        putRaw(prefix + "runner-manifest.json", "{\"planDigest\":\"sha256:abc\"}");

        assertThat(store.listForRun(runId))
                .extracting(ArtifactReference::type)
                .containsExactlyInAnyOrder(ArtifactType.RESULTS, ArtifactType.RUNNER_LOG,
                        ArtifactType.RUNNER_MANIFEST);
    }

    /**
     * Analysis matches a shard's results to the shard that produced them. A reference that lost the
     * shard would never match, which is the second half of the same failure: results present in
     * storage, adopted into the catalog, and still invisible to analysis.
     */
    @Test
    @DisplayName("the shard that produced an artifact is recovered from its key")
    void recoversTheShardFromTheKey() {
        TeamId teamId = TeamId.generate();
        TestRunId runId = TestRunId.generate();
        RunnerShardId shardId = RunnerShardId.generate();

        putRaw("teams/%s/runs/%s/shards/%s/results.jtl"
                .formatted(teamId.value(), runId.value(), shardId.value()), "timeStamp\n");

        assertThat(store.listForRun(runId))
                .singleElement()
                .satisfies(reference -> assertThat(reference.shardId()).contains(shardId));
    }

    /**
     * A dataset partition is a CSV. So is a JMeter results file.
     *
     * <p>Without something in the stored name to tell them apart, a run's own input would be adopted
     * as its output: thousands of rows of order identifiers parsed as samples, contributing to the
     * latency and error figures a verdict is drawn from. Nothing would look wrong — the same class of
     * failure as a synthetic run reading as a pass. The {@code dataset-} prefix is what prevents it,
     * which is why it is part of the key contract rather than a naming convention.
     */
    @Test
    @DisplayName("a dataset partition is never mistaken for results, though both are CSV")
    void datasetPartitionIsNotResults() {
        TeamId teamId = TeamId.generate();
        TestRunId runId = TestRunId.generate();
        RunnerShardId shardId = RunnerShardId.generate();
        String prefix = "teams/%s/runs/%s/shards/%s/"
                .formatted(teamId.value(), runId.value(), shardId.value());

        putRaw(prefix + "dataset-client-data.csv", "orderId,customerId\nO-1,C-1\n");
        putRaw(prefix + "results.jtl", "timeStamp,elapsed,label\n1,2,browse\n");

        assertThat(store.listForRun(runId))
                .extracting(ArtifactReference::type)
                .containsExactlyInAnyOrder(ArtifactType.DATASET, ArtifactType.RESULTS);
    }

    /**
     * Defaulting would be worse than ignoring: an unknown object adopted as {@code RESULTS} would be
     * parsed as measurements and could quietly change a verdict.
     */
    @Test
    @DisplayName("an object with an unrecognised name is ignored rather than guessed at")
    void ignoresUnrecognisedObjects() {
        TestRunId runId = TestRunId.generate();
        putRaw("teams/%s/runs/%s/shards/%s/notes.txt"
                .formatted(TeamId.generate().value(), runId.value(), RunnerShardId.generate().value()),
                "not an artifact");

        assertThat(store.listForRun(runId)).isEmpty();
    }

    /** Uploads the way a runner does — no platform metadata, no digest header. */
    private static void putRaw(String key, String body) {
        s3.putObject(software.amazon.awssdk.services.s3.model.PutObjectRequest.builder()
                        .bucket(BUCKET).key(key).build(),
                software.amazon.awssdk.core.sync.RequestBody.fromString(body));
    }

    /** One run's listing must never reach into another's, even within the same team. */
    @Test
    @DisplayName("listing one run does not return another run's artifacts")
    void listingIsScopedToOneRun() {
        TeamId teamId = TeamId.generate();
        TestRunId mine = TestRunId.generate();
        TestRunId theirs = TestRunId.generate();

        store.store(descriptor(teamId, mine, Optional.empty(), ArtifactType.RESULTS,
                "results.jtl", "text/csv"), new byte[] {1});
        store.store(descriptor(teamId, theirs, Optional.empty(), ArtifactType.RESULTS,
                "results.jtl", "text/csv"), new byte[] {2});

        assertThat(store.listForRun(mine)).hasSize(1);
        assertThat(store.listForRun(mine).get(0).objectKey()).contains(mine.value().toString());
    }
}
