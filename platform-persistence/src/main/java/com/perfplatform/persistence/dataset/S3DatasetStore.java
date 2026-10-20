package com.perfplatform.persistence.dataset;

import com.perfplatform.domain.dataset.DatasetUri;
import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.port.ArtifactStore.ArtifactStoreException;
import com.perfplatform.domain.port.DatasetStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.util.Objects;
import java.util.Optional;

/**
 * Dataset content in S3-compatible object storage.
 *
 * <p>The production implementation. It shares the bucket with artifacts and differs only in key
 * prefix — {@code teams/{teamId}/datasets/…} rather than {@code teams/{teamId}/runs/…} — so a bucket
 * policy written per team covers both without anybody having to know there are two ports.
 *
 * <p>No pre-signed URL is minted or stored, here or anywhere (CLAUDE.md §17). A dataset is read by
 * the control plane when it slices it, and by a runner using its own identity.
 */
public class S3DatasetStore implements DatasetStore {

    private static final Logger log = LoggerFactory.getLogger(S3DatasetStore.class);

    private static final String CONTENT_TYPE = "text/csv";

    private final S3Client s3;
    private final String bucket;

    public S3DatasetStore(S3Client s3, String bucket) {
        this.s3 = Objects.requireNonNull(s3, "s3");
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalArgumentException("A dataset bucket must be configured");
        }
        this.bucket = bucket;
        log.info("Datasets are stored in S3-compatible object storage, bucket={}", bucket);
    }

    @Override
    public StoredContent store(TeamId teamId, DatasetUri uri, byte[] content) {
        byte[] body = content == null ? new byte[0] : content;
        String objectKey = DatasetKeys.keyFor(teamId, uri);

        try {
            s3.putObject(PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(objectKey)
                            .contentType(CONTENT_TYPE)
                            .contentLength((long) body.length)
                            .build(),
                    RequestBody.fromBytes(body));
        } catch (S3Exception e) {
            // The SDK's message can carry bucket policy and endpoint detail; it goes to the log
            // behind this exception and the caller gets the port's own type.
            throw new ArtifactStoreException("Could not store dataset " + objectKey, e);
        }

        return new StoredContent(objectKey, DatasetKeys.digestOf(body), body.length);
    }

    @Override
    public Optional<byte[]> read(String objectKey) {
        try {
            ResponseBytes<GetObjectResponse> object = s3.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(bucket).key(objectKey).build());
            return Optional.of(object.asByteArray());
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            throw new ArtifactStoreException("Could not read dataset " + objectKey, e);
        }
    }
}
