package com.perfplatform.persistence.definition;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The mutable handle for a test: its name, its owner, and a pointer to its latest version.
 *
 * <p>The definition changes over time; its versions do not. Runs reference a version, never this
 * row, so renaming a definition cannot alter what a past run executed.
 */
@Entity
@Table(name = "test_definitions")
public class TestDefinitionEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "team_id", nullable = false, updatable = false)
    private UUID teamId;

    @Column(name = "application_id", nullable = false, updatable = false)
    private UUID applicationId;

    @Column(name = "name", nullable = false, length = 63)
    private String name;

    /** Zero until the first version is created; version numbers start at 1. */
    @Column(name = "latest_version", nullable = false)
    private int latestVersion;

    @Column(name = "created_by", nullable = false, updatable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TestDefinitionEntity() {
        // Required by JPA.
    }

    public TestDefinitionEntity(UUID id, UUID teamId, UUID applicationId, String name,
            String createdBy, Instant now) {
        this.id = Objects.requireNonNull(id, "id");
        this.teamId = Objects.requireNonNull(teamId, "teamId");
        this.applicationId = Objects.requireNonNull(applicationId, "applicationId");
        this.name = Objects.requireNonNull(name, "name");
        this.createdBy = Objects.requireNonNull(createdBy, "createdBy");
        this.createdAt = Objects.requireNonNull(now, "now");
        this.updatedAt = now;
        this.latestVersion = 0;
    }

    /** Advances the latest-version pointer after a new version is stored. */
    void recordNewVersion(int versionNumber, Instant now) {
        if (versionNumber != latestVersion + 1) {
            throw new IllegalStateException(
                    "Version numbers must be contiguous: expected " + (latestVersion + 1)
                            + " but was " + versionNumber);
        }
        this.latestVersion = versionNumber;
        this.updatedAt = now;
    }

    public int nextVersionNumber() {
        return latestVersion + 1;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTeamId() {
        return teamId;
    }

    public UUID getApplicationId() {
        return applicationId;
    }

    public String getName() {
        return name;
    }

    public int getLatestVersion() {
        return latestVersion;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
