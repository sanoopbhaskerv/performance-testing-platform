package com.perfplatform.persistence.artifact;

import com.perfplatform.domain.port.ArtifactStore.ArtifactType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Storage row for an artifact reference.
 *
 * <p>The reference, never the content. Content lives in the artifact store — the filesystem locally,
 * object storage in production — and a database row holding a megabyte of JTL would make every
 * query that touches a run pay for it.
 *
 * <p>Every column is {@code updatable = false}. An artifact is written once; changing what a stored
 * key or digest says would break the link between a run's evidence and the run itself.
 */
@Entity
@Table(name = "artifacts")
public class ArtifactEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "run_id", nullable = false, updatable = false)
    private UUID runId;

    @Column(name = "shard_id", updatable = false)
    private UUID shardId;

    @Enumerated(EnumType.STRING)
    @Column(name = "artifact_type", nullable = false, updatable = false, length = 32)
    private ArtifactType artifactType;

    /** A stable storage key, never a pre-signed URL: a stored URL expires (CLAUDE.md §17). */
    @Column(name = "object_key", nullable = false, updatable = false, length = 1024)
    private String objectKey;

    @Column(name = "digest", nullable = false, updatable = false, length = 80)
    private String digest;

    @Column(name = "content_type", nullable = false, updatable = false, length = 128)
    private String contentType;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    @Column(name = "stored_at", nullable = false, updatable = false)
    private Instant storedAt;

    /** When this may be reclaimed. Set by the retention policy, which is Increment 1E's. */
    @Column(name = "expires_at")
    private Instant expiresAt;

    protected ArtifactEntity() {
        // Required by JPA.
    }

    ArtifactEntity(UUID id, UUID runId, UUID shardId, ArtifactType artifactType, String objectKey,
            String digest, String contentType, long sizeBytes, Instant storedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.runId = Objects.requireNonNull(runId, "runId");
        this.shardId = shardId;
        this.artifactType = Objects.requireNonNull(artifactType, "artifactType");
        this.objectKey = Objects.requireNonNull(objectKey, "objectKey");
        this.digest = Objects.requireNonNull(digest, "digest");
        this.contentType = Objects.requireNonNull(contentType, "contentType");
        this.sizeBytes = sizeBytes;
        this.storedAt = Objects.requireNonNull(storedAt, "storedAt");
    }

    public UUID getId() {
        return id;
    }

    public UUID getRunId() {
        return runId;
    }

    public UUID getShardId() {
        return shardId;
    }

    public ArtifactType getArtifactType() {
        return artifactType;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public String getDigest() {
        return digest;
    }

    public String getContentType() {
        return contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public Instant getStoredAt() {
        return storedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
