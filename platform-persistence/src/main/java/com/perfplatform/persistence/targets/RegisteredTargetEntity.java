package com.perfplatform.persistence.targets;

import com.perfplatform.domain.spec.TargetClass;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "registered_targets")
public class RegisteredTargetEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "team_id", nullable = false, updatable = false)
    private UUID teamId;

    @Column(name = "application_id", nullable = false, updatable = false)
    private UUID applicationId;

    @Column(name = "application_name", nullable = false, length = 63)
    private String applicationName;

    @Column(name = "environment", nullable = false, length = 63)
    private String environment;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_class", nullable = false, length = 32)
    private TargetClass targetClass;

    @Column(name = "scheme", nullable = false, length = 8)
    private String scheme;

    @Column(name = "host", nullable = false, length = 255)
    private String host;

    @Column(name = "port", nullable = false)
    private int port;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected RegisteredTargetEntity() {
        // Required by JPA.
    }

    RegisteredTargetEntity(UUID id, UUID teamId, UUID applicationId, String applicationName,
            String environment, TargetClass targetClass, String scheme, String host, int port,
            String createdBy, Instant now) {
        this.id = Objects.requireNonNull(id, "id");
        this.teamId = Objects.requireNonNull(teamId, "teamId");
        this.applicationId = Objects.requireNonNull(applicationId, "applicationId");
        this.applicationName = Objects.requireNonNull(applicationName, "applicationName");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.targetClass = Objects.requireNonNull(targetClass, "targetClass");
        this.scheme = Objects.requireNonNull(scheme, "scheme");
        this.host = Objects.requireNonNull(host, "host");
        this.port = port;
        this.createdBy = Objects.requireNonNull(createdBy, "createdBy");
        this.createdAt = Objects.requireNonNull(now, "now");
        this.updatedAt = now;
    }

    UUID getTeamId() {
        return teamId;
    }

    UUID getApplicationId() {
        return applicationId;
    }

    String getApplicationName() {
        return applicationName;
    }

    String getEnvironment() {
        return environment;
    }

    TargetClass getTargetClass() {
        return targetClass;
    }

    String getScheme() {
        return scheme;
    }

    String getHost() {
        return host;
    }

    int getPort() {
        return port;
    }

    String getCreatedBy() {
        return createdBy;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    void replace(String applicationName, TargetClass targetClass, String scheme, String host,
            int port, String actor, Instant now) {
        this.applicationName = Objects.requireNonNull(applicationName, "applicationName");
        this.targetClass = Objects.requireNonNull(targetClass, "targetClass");
        this.scheme = Objects.requireNonNull(scheme, "scheme");
        this.host = Objects.requireNonNull(host, "host");
        this.port = port;
        this.createdBy = Objects.requireNonNull(actor, "actor");
        this.updatedAt = Objects.requireNonNull(now, "now");
    }
}
