package com.perfplatform.persistence.definition;

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
 * An application owned by a team.
 *
 * <p>Carries the target classification that governs approval policy. It is held here, server-side,
 * rather than taken from a submitted specification: a specification that could declare its own
 * target class could promote a production target to non-production and bypass approval entirely.
 */
@Entity
@Table(name = "applications")
public class ApplicationEntity {

    public enum TargetClass {
        NON_PRODUCTION,
        PRODUCTION
    }

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "team_id", nullable = false, updatable = false)
    private UUID teamId;

    @Column(name = "name", nullable = false, length = 63)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_class", nullable = false, length = 32)
    private TargetClass targetClass = TargetClass.NON_PRODUCTION;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ApplicationEntity() {
        // Required by JPA.
    }

    public ApplicationEntity(UUID id, UUID teamId, String name, TargetClass targetClass, Instant now) {
        this.id = Objects.requireNonNull(id, "id");
        this.teamId = Objects.requireNonNull(teamId, "teamId");
        this.name = Objects.requireNonNull(name, "name");
        this.targetClass = Objects.requireNonNull(targetClass, "targetClass");
        this.createdAt = Objects.requireNonNull(now, "now");
        this.updatedAt = now;
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

    public TargetClass getTargetClass() {
        return targetClass;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
