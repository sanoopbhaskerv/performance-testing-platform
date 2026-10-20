package com.perfplatform.persistence.run;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import com.perfplatform.domain.run.RunFailureCode;
import com.perfplatform.domain.run.RunState;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Storage row for a run.
 *
 * <p>Mutable and framework-coupled by necessity; the domain's {@code TestRun} is neither. The two
 * are kept apart deliberately — a domain record shaped by JPA acquires setters, a no-arg constructor
 * and nullable fields for the ORM's benefit, and stops being able to enforce its own invariants.
 * {@link TestRunMapper} converts between them.
 *
 * <p>Never returned from the API. Exposing an entity makes the storage schema the public contract,
 * which is the defect V1 has: adding a column changes the API, and renaming one breaks consumers.
 */
@Entity
@Table(name = "test_runs")
public class TestRunEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "team_id", nullable = false, updatable = false)
    private UUID teamId;

    @Column(name = "definition_version_id", nullable = false, updatable = false)
    private UUID definitionVersionId;

    @Column(name = "execution_plan_id")
    private UUID executionPlanId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private RunState status;

    @Column(name = "environment", nullable = false, updatable = false, length = 63)
    private String environment;

    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 200)
    private String idempotencyKey;

    @Column(name = "request_digest", nullable = false, updatable = false, length = 80)
    private String requestDigest;

    @Column(name = "requested_by", nullable = false, updatable = false)
    private String requestedBy;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "queued_at")
    private Instant queuedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "cancellation_requested_by")
    private String cancellationRequestedBy;

    @Column(name = "cancellation_requested_at")
    private Instant cancellationRequestedAt;

    @Column(name = "cancellation_reason", length = 500)
    private String cancellationReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_code", length = 64)
    private RunFailureCode failureCode;

    @Column(name = "failure_reason", length = 2000)
    private String failureReason;

    @Column(name = "claim_owner")
    private String claimOwner;

    @Column(name = "claim_expires_at")
    private Instant claimExpiresAt;

    /**
     * Whether the provider that executed this run generates no load.
     *
     * <p>Primitive rather than {@code Boolean}, so it can never be null and a read never has to
     * decide what an absent value means. The column defaults to false for rows that predate it.
     */
    @Column(name = "synthetic", nullable = false)
    private boolean synthetic;

    /**
     * Optimistic lock.
     *
     * <p>Concurrent state changes are the normal case here, not an edge: a reconciler advancing a
     * run and a consumer cancelling it race routinely. Without this, the later write wins silently
     * and a cancellation is lost.
     */
    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    protected TestRunEntity() {
        // Required by JPA.
    }

    TestRunEntity(UUID id, UUID teamId, UUID definitionVersionId, RunState status,
            String environment, String idempotencyKey, String requestDigest, String requestedBy,
            Instant requestedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.teamId = Objects.requireNonNull(teamId, "teamId");
        this.definitionVersionId = Objects.requireNonNull(definitionVersionId, "definitionVersionId");
        this.status = Objects.requireNonNull(status, "status");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        this.requestDigest = Objects.requireNonNull(requestDigest, "requestDigest");
        this.requestedBy = Objects.requireNonNull(requestedBy, "requestedBy");
        this.requestedAt = Objects.requireNonNull(requestedAt, "requestedAt");
    }

    public UUID getId() {
        return id;
    }

    public UUID getTeamId() {
        return teamId;
    }

    public UUID getDefinitionVersionId() {
        return definitionVersionId;
    }

    public UUID getExecutionPlanId() {
        return executionPlanId;
    }

    void setExecutionPlanId(UUID executionPlanId) {
        this.executionPlanId = executionPlanId;
    }

    public RunState getStatus() {
        return status;
    }

    void setStatus(RunState status) {
        this.status = status;
    }

    public String getEnvironment() {
        return environment;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestDigest() {
        return requestDigest;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public Instant getQueuedAt() {
        return queuedAt;
    }

    void setQueuedAt(Instant queuedAt) {
        this.queuedAt = queuedAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public String getCancellationRequestedBy() {
        return cancellationRequestedBy;
    }

    public Instant getCancellationRequestedAt() {
        return cancellationRequestedAt;
    }

    public String getCancellationReason() {
        return cancellationReason;
    }

    void setCancellation(String requestedBy, Instant requestedAt, String reason) {
        this.cancellationRequestedBy = requestedBy;
        this.cancellationRequestedAt = requestedAt;
        this.cancellationReason = reason;
    }

    public RunFailureCode getFailureCode() {
        return failureCode;
    }

    public String getFailureReason() {
        return failureReason;
    }

    void setFailure(RunFailureCode code, String reason) {
        this.failureCode = code;
        this.failureReason = reason;
    }

    public String getClaimOwner() {
        return claimOwner;
    }

    public Instant getClaimExpiresAt() {
        return claimExpiresAt;
    }

    void setClaim(String owner, Instant expiresAt) {
        this.claimOwner = owner;
        this.claimExpiresAt = expiresAt;
    }

    public boolean isSynthetic() {
        return synthetic;
    }

    /**
     * Marks the run as having generated no load. One-way.
     *
     * <p>There is deliberately no setter that can clear it. A run whose traffic was invented cannot
     * later become one whose traffic was real, and a mapper copying {@code false} over a stored
     * {@code true} — an easy accident, since every save calls the mapper — would erase the most
     * consequential caveat this platform records.
     */
    void markSynthetic() {
        this.synthetic = true;
    }

    public long getLockVersion() {
        return lockVersion;
    }
}
