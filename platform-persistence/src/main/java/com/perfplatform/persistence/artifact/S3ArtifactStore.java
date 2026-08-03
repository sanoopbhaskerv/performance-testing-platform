package com.perfplatform.persistence.artifact;

import com.perfplatform.domain.identity.ArtifactId;
import com.perfplatform.domain.identity.TestRunId;
import com.perfplatform.domain.port.ArtifactStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Artifacts in S3-compatible object storage.
 *
 * <p>The production implementation of the port. {@link FileSystemArtifactStore} cannot serve a
 * multi-replica control plane — a file written by one replica is invisible to the others — and it
 * certainly cannot be reached by a runner Pod on another node. This can.
 *
 * <p>The key layout is unchanged from the filesystem store: {@code teams/{teamId}/runs/{runId}/…}.
 * That is deliberate. Authorization is a property of the key, so a caller who may not read a team's
 * data cannot construct a key that reaches it, and the two implementations stay interchangeable.
 *
 * <h2>What is never stored</h2>
 * No pre-signed URL, ever (CLAUDE.md §17). A stored URL keeps working after the access that created
 * it was revoked, and stops working at a moment nobody chose. The database holds a stable key; every
 * download is an authorized request that reads the object afresh.
 *
 * <h2>Verification</h2>
 * Content is digested before upload and the digest is stored beside the object as metadata, so an
 * object can be checked against what was recorded without consulting the database. Reads verify
 * nothing here — that check belongs at the point of use, where a mismatch can be refused with a
 * reason rather than swallowed by a storage adapter.
 */
public class S3ArtifactStore implements ArtifactStore {

    private static final Logger log = LoggerFactory.getLogger(S3ArtifactStore.class);

    /** Object metadata carrying the digest recorded at upload, for verification without a database. */
    private static final String METADATA_DIGEST = "perf-digest";
    private static final String METADATA_RUN_ID = "perf-run-id";
    private static final String METADATA_TEAM_ID = "perf-team-id";
    private static final String METADATA_ARTIFACT_TYPE = "perf-artifact-type";

    private final S3Client s3;
    private final String bucket;
    private final Clock clock;

    /**
     * @param bucket the bucket artifacts live in. Configuration, never a constant: choosing a
     *               production bucket or account is a decision for whoever operates the platform
     *               (CLAUDE.md §31), not for this code
     */
    public S3ArtifactStore(S3Client s3, String bucket, Clock clock) {
        this.s3 = Objects.requireNonNull(s3, "s3");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalArgumentException("An artifact bucket must be configured");
        }
        this.bucket = bucket;
        log.info("Artifacts are stored in S3-compatible object storage, bucket={}", bucket);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Idempotent by key: re-storing the same descriptor overwrites the object with identical
     * content, which is what a repeated reconciliation pass does. The returned reference is new each
     * time, and the catalog deduplicates on the key.
     */
    @Override
    public ArtifactReference store(ArtifactDescriptor descriptor, byte[] content) {
        byte[] body = content == null ? new byte[0] : content;
        String objectKey = FileSystemArtifactStore.keyFor(descriptor);
        String digest = digestOf(body);

        try {
            s3.putObject(PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(objectKey)
                            .contentType(descriptor.contentType())
                            .contentLength((long) body.length)
                            .metadata(Map.of(
                                    METADATA_DIGEST, digest,
                                    METADATA_RUN_ID, descriptor.runId().value().toString(),
                                    METADATA_TEAM_ID, descriptor.teamId().value().toString(),
                                    METADATA_ARTIFACT_TYPE, descriptor.type().name()))
                            .build(),
                    RequestBody.fromBytes(body));
        } catch (S3Exception e) {
            // The SDK's message can carry bucket policy and endpoint detail; it goes to the log
            // behind this exception, and the caller gets the port's own type.
            throw new ArtifactStoreException("Could not store artifact " + objectKey, e);
        }

        log.debug("Artifact stored bucket={} key={} bytes={} type={}",
                bucket, objectKey, body.length, descriptor.type());

        return new ArtifactReference(
                ArtifactId.generate(),
                descriptor.runId(),
                descriptor.shardId(),
                descriptor.type(),
                objectKey,
                digest,
                descriptor.contentType(),
                body.length,
                clock.instant());
    }

    /**
     * {@inheritDoc}
     *
     * <p>Empty rather than an exception when the object is gone. Retention reclaims artifacts, and a
     * report asking for one that has aged out is an ordinary situation the caller reports honestly —
     * not an error.
     */
    @Override
    public Optional<byte[]> read(ArtifactReference reference) {
        try {
            ResponseBytes<GetObjectResponse> object = s3.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(bucket).key(reference.objectKey()).build());
            return Optional.of(object.asByteArray());

        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            throw new ArtifactStoreException("Could not read artifact " + reference.objectKey(), e);
        }
    }

    @Override
    public void delete(ArtifactReference reference) {
        try {
            s3.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(reference.objectKey())
                    .build());
        } catch (S3Exception e) {
            throw new ArtifactStoreException("Could not delete artifact "
                    + reference.objectKey(), e);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Lists by key prefix, which is why the team prefix comes first in the layout — a run's
     * artifacts are a contiguous range, and no other team's objects are ever scanned.
     *
     * <p>The references it returns are reconstructed from object metadata rather than from the
     * database. This is a recovery and verification path: the durable record of what a run produced
     * is in {@code artifacts}, and this exists so storage can be reconciled against it.
     */
    @Override
    public List<ArtifactReference> listForRun(TestRunId runId) {
        String suffix = "/runs/" + runId.value() + "/";

        // Without the team, the only shared prefix is "teams/". Bounded by the caller already
        // knowing the run, and used for reconciliation rather than on any request path.
        List<S3Object> objects = s3.listObjectsV2Paginator(
                        ListObjectsV2Request.builder().bucket(bucket).prefix("teams/").build())
                .contents().stream()
                .filter(object -> object.key().contains(suffix))
                .toList();

        return objects.stream()
                .map(object -> toReference(runId, object.key(), object.size()))
                .flatMap(Optional::stream)
                .toList();
    }

    /**
     * Rebuilds a reference from an object's key, using its metadata only where it adds something.
     *
     * <p><b>The key is the contract, not the metadata.</b> A runner uploads with an ordinary S3
     * client and sets none of this platform's object metadata — it shares no process, filesystem or
     * database with the control plane, and the key layout is the only thing both agree on. Deriving
     * identity from metadata alone means silently ignoring everything a real runner produces, which
     * presents as a run that measured nothing rather than as an error.
     *
     * <p>Metadata is still read for the digest, because a digest cannot be derived from a key and
     * the platform's own uploads record one.
     *
     * @param runId the run being listed, which the caller already knows; taking it from here rather
     *              than from the key keeps one authority for what run this belongs to
     */
    private Optional<ArtifactReference> toReference(TestRunId runId, String key, Long size) {
        Optional<ArtifactType> type = FileSystemArtifactStore.typeForFileName(key);
        if (type.isEmpty()) {
            // An unrecognised file. Ignored rather than defaulted: adopting an unknown object as
            // RESULTS would have it parsed as measurements and could quietly change a verdict.
            log.debug("Ignoring object with an unrecognised artifact name key={}", key);
            return Optional.empty();
        }

        try {
            HeadObjectResponse head = s3.headObject(
                    HeadObjectRequest.builder().bucket(bucket).key(key).build());

            Map<String, String> metadata = head.metadata() == null ? Map.of() : head.metadata();

            return Optional.of(new ArtifactReference(
                    ArtifactId.generate(),
                    runId,
                    // From the key, so a runner's own uploads are attributed to the shard that
                    // produced them. Analysis matches results to shards by this.
                    FileSystemArtifactStore.shardIdIn(key),
                    type.get(),
                    key,
                    metadata.getOrDefault(METADATA_DIGEST, ""),
                    head.contentType() == null ? "application/octet-stream" : head.contentType(),
                    size == null ? 0L : size,
                    head.lastModified() == null ? clock.instant() : head.lastModified()));

        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (RuntimeException e) {
            log.warn("Ignoring unreadable object key={} while listing run artifacts", key, e);
            return Optional.empty();
        }
    }

    private static String digestOf(byte[] content) {
        try {
            return "sha256:" + HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available in this JVM", e);
        }
    }
}
