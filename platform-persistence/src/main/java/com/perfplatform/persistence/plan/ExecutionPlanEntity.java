package com.perfplatform.persistence.plan;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Storage row for a compiled execution plan.
 *
 * <p>Written once. Every column is {@code updatable = false} and there are no setters, because a run
 * references this plan and runner results are verified against its digest — rewriting it would
 * invalidate every result already accepted, retroactively and silently. PostgreSQL enforces the same
 * rule with a trigger for writes that never pass through JPA.
 */
@Entity
@Table(name = "execution_plans")
public class ExecutionPlanEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "definition_version_id", nullable = false, updatable = false)
    private UUID definitionVersionId;

    @Column(name = "engine", nullable = false, updatable = false, length = 32)
    private String engine;

    /**
     * The compiled plan, stored as text.
     *
     * <p>Not a JSON column type. JSONB is a parsed representation and reorders object keys on the
     * way back out, so the text read would not be the text written — and the digest below is taken
     * over exactly these bytes. Storing it as text is what lets a stored plan and its stored digest
     * still agree years later.
     */
    @Column(name = "plan_json", nullable = false, updatable = false)
    private String planJson;

    @Column(name = "plan_digest", nullable = false, updatable = false, length = 80)
    private String planDigest;

    @Column(name = "shard_count", nullable = false, updatable = false)
    private int shardCount;

    @Column(name = "peak_virtual_users", nullable = false, updatable = false)
    private int peakVirtualUsers;

    @Column(name = "estimated_duration_seconds", nullable = false, updatable = false)
    private long estimatedDurationSeconds;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ExecutionPlanEntity() {
        // Required by JPA.
    }

    ExecutionPlanEntity(UUID id, UUID definitionVersionId, String engine, String planJson,
            String planDigest, int shardCount, int peakVirtualUsers, long estimatedDurationSeconds,
            Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.definitionVersionId = Objects.requireNonNull(definitionVersionId, "definitionVersionId");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.planJson = Objects.requireNonNull(planJson, "planJson");
        this.planDigest = Objects.requireNonNull(planDigest, "planDigest");
        this.shardCount = shardCount;
        this.peakVirtualUsers = peakVirtualUsers;
        this.estimatedDurationSeconds = estimatedDurationSeconds;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public UUID getId() {
        return id;
    }

    public UUID getDefinitionVersionId() {
        return definitionVersionId;
    }

    public String getEngine() {
        return engine;
    }

    public String getPlanJson() {
        return planJson;
    }

    public String getPlanDigest() {
        return planDigest;
    }

    public int getShardCount() {
        return shardCount;
    }

    public int getPeakVirtualUsers() {
        return peakVirtualUsers;
    }

    public long getEstimatedDurationSeconds() {
        return estimatedDurationSeconds;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
