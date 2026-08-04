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
     * The workload this plan executes, unique within its run.
     *
     * <p>Part of the plan's identity rather than a label on it: it is what tells two plans of one
     * run apart, and what the run's shards and samples are scoped by. Rows that predate
     * per-workload plans carry {@code *}, where one plan was the whole of a run's execution.
     */
    @Column(name = "workload_name", nullable = false, updatable = false, length = 63)
    private String workloadName;

    /**
     * The run this plan was compiled for.
     *
     * <p>Nullable only for rows written before plans belonged to runs. Written together with the
     * plan itself, so an interrupted pass cannot leave a plan that no run claims — which would make
     * the next pass compile a second one and start a second set of runners.
     */
    @Column(name = "run_id", updatable = false)
    private UUID runId;

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

    ExecutionPlanEntity(UUID id, UUID definitionVersionId, String workloadName, UUID runId,
            String engine, String planJson,
            String planDigest, int shardCount, int peakVirtualUsers, long estimatedDurationSeconds,
            Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.definitionVersionId = Objects.requireNonNull(definitionVersionId, "definitionVersionId");
        this.workloadName = Objects.requireNonNull(workloadName, "workloadName");
        this.runId = runId;
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

    public String getWorkloadName() {
        return workloadName;
    }

    public UUID getRunId() {
        return runId;
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
