package com.perfplatform.persistence.source;

import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.port.BrowserSourceStore;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import java.util.Optional;

/** Content-addressed sources share the artifact bucket and its team-scoped access policy. */
public final class S3BrowserSourceStore implements BrowserSourceStore {
    private final S3Client s3;
    private final String bucket;
    public S3BrowserSourceStore(S3Client s3, String bucket) { this.s3 = s3; this.bucket = bucket; }

    @Override
    public void put(TeamId team, String digest, byte[] content) {
        if (!BrowserSourceStore.digest(content).equals(digest)) throw new IllegalArgumentException("Source digest mismatch");
        s3.putObject(PutObjectRequest.builder().bucket(bucket).key(BrowserSourceStore.key(team, digest))
                .contentType("text/plain; charset=utf-8").build(), RequestBody.fromBytes(content));
    }

    @Override
    public Optional<byte[]> read(TeamId team, String digest) {
        try {
            return Optional.of(s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket)
                    .key(BrowserSourceStore.key(team, digest)).build()).asByteArray());
        } catch (NoSuchKeyException e) { return Optional.empty(); }
    }
}
