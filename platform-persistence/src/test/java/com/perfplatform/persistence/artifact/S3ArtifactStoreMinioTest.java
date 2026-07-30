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
import org.testcontainers.containers.MinIOContainer;
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
    static final MinIOContainer MINIO = new MinIOContainer("minio/minio:RELEASE.2024-11-07T00-52-20Z");

    private static S3Client s3;
    private static S3ArtifactStore store;

    @BeforeAll
    static void createBucket() {
        s3 = S3Client.builder()
                .endpointOverride(URI.create(MINIO.getS3URL()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(MINIO.getUserName(), MINIO.getPassword())))
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
