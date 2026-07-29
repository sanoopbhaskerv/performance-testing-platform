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
 * A team. Every tenant-owned row references one, and authorization is evaluated against it on
 * every request.
 *
 * <p>V1 has no teams table: a team exists only as a string in a YAML file loaded into each
 * replica's memory, which is why two replicas could disagree about which teams exist and why an
 * API key could not be bound to one.
 */
@Entity
@Table(name = "teams")
public class TeamEntity {

    public enum Status {
        ACTIVE,
        /** Retained for audit and history, but may not create definitions or trigger runs. */
        SUSPENDED
    }

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "name", nullable = false, length = 63)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private Status status = Status.ACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TeamEntity() {
        // Required by JPA.
    }

    public TeamEntity(UUID id, String name, Instant now) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = Objects.requireNonNull(name, "name");
        this.createdAt = Objects.requireNonNull(now, "now");
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Status getStatus() {
        return status;
    }

    public void suspend(Instant now) {
        this.status = Status.SUSPENDED;
        this.updatedAt = now;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
