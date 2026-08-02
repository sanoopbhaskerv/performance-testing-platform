package com.perfplatform.persistence.dataset;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Storage row for one immutable dataset version.
 *
 * <p>The record, never the content — a database row holding a multi-megabyte CSV would make every
 * query that touches a team pay for it. Every column is {@code updatable = false}: a version whose
 * columns or digest could change is a test whose inputs changed without its specification changing.
 */
@Entity
@Table(name = "datasets")
public class DatasetEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "team_id", nullable = false, updatable = false)
    private UUID teamId;

    @Column(name = "name", nullable = false, updatable = false, length = 63)
    private String name;

    @Column(name = "version", nullable = false, updatable = false)
    private int version;

    /** A stable storage key, never a pre-signed URL: a stored URL expires (CLAUDE.md §17). */
    @Column(name = "object_key", nullable = false, updatable = false, length = 1024)
    private String objectKey;

    @Column(name = "digest", nullable = false, updatable = false, length = 80)
    private String digest;

    /** The header the file actually had, comma-separated in file order. */
    @Column(name = "columns_csv", nullable = false, updatable = false, length = 4000)
    private String columnsCsv;

    @Column(name = "row_count", nullable = false, updatable = false)
    private long rowCount;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    @Column(name = "uploaded_by", nullable = false, updatable = false, length = 255)
    private String uploadedBy;

    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private Instant uploadedAt;

    protected DatasetEntity() {
        // Required by JPA.
    }

    DatasetEntity(UUID id, UUID teamId, String name, int version, String objectKey, String digest,
            String columnsCsv, long rowCount, long sizeBytes, String uploadedBy, Instant uploadedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.teamId = Objects.requireNonNull(teamId, "teamId");
        this.name = Objects.requireNonNull(name, "name");
        this.version = version;
        this.objectKey = Objects.requireNonNull(objectKey, "objectKey");
        this.digest = Objects.requireNonNull(digest, "digest");
        this.columnsCsv = Objects.requireNonNull(columnsCsv, "columnsCsv");
        this.rowCount = rowCount;
        this.sizeBytes = sizeBytes;
        this.uploadedBy = Objects.requireNonNull(uploadedBy, "uploadedBy");
        this.uploadedAt = Objects.requireNonNull(uploadedAt, "uploadedAt");
    }

    public UUID getId() {
        return id;
    }

    public UUID getTeamId() {
        return teamId;
    }

    public String getName() {
        return name;
    }

    public int getVersion() {
        return version;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public String getDigest() {
        return digest;
    }

    public String getColumnsCsv() {
        return columnsCsv;
    }

    public long getRowCount() {
        return rowCount;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getUploadedBy() {
        return uploadedBy;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }
}
