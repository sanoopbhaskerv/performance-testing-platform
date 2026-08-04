package com.perfplatform.persistence.run;

import com.perfplatform.domain.run.RunFailureCode;
import com.perfplatform.domain.run.ShardState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Storage row for one runner shard.
 *
 * <p>The external handle columns are the important ones. They are written before the run is allowed
 * to report {@code RUNNING}, so that a control-plane crash can never leave a load generator
 * executing with no durable record of how to find or stop it.
 */
@Entity
@Table(name = "runner_shards")
public class RunnerShardEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "run_id", nullable = false, updatable = false)
    private UUID runId;

    @Column(name = "engine", nullable = false, updatable = false, length = 32)
    private String engine;

    /**
     * The workload this shard executes.
     *
     * <p>Two workloads of one run can each have a shard 0, so a shard's identity within a run is its
     * workload and index together. Rows written before per-workload plans carry {@code *}.
     */
    @Column(name = "workload_name", nullable = false, updatable = false, length = 63)
    private String workloadName;

    @Column(name = "shard_index", nullable = false, updatable = false)
    private int shardIndex;

    @Column(name = "shard_count", nullable = false, updatable = false)
    private int shardCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ShardState status;

    @Column(name = "provider_id", length = 32)
    private String providerId;

    @Column(name = "external_name", length = 253)
    private String externalName;

    /** The provider's stable identifier for this instance, kept so a reused name cannot mislead. */
    @Column(name = "external_uid", length = 128)
    private String externalUid;

    @Column(name = "last_heartbeat_at")
    private Instant lastHeartbeatAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "exit_code")
    private Integer exitCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_code", length = 64)
    private RunFailureCode failureCode;

    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    protected RunnerShardEntity() {
        // Required by JPA.
    }

    RunnerShardEntity(UUID id, UUID runId, String engine, String workloadName, int shardIndex,
            int shardCount, ShardState status) {
        this.id = Objects.requireNonNull(id, "id");
        this.runId = Objects.requireNonNull(runId, "runId");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.workloadName = Objects.requireNonNull(workloadName, "workloadName");
        this.shardIndex = shardIndex;
        this.shardCount = shardCount;
        this.status = Objects.requireNonNull(status, "status");
    }

    public UUID getId() {
        return id;
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

    public int getShardIndex() {
        return shardIndex;
    }

    public int getShardCount() {
        return shardCount;
    }

    public ShardState getStatus() {
        return status;
    }

    void setStatus(ShardState status) {
        this.status = status;
    }

    public String getProviderId() {
        return providerId;
    }

    public String getExternalName() {
        return externalName;
    }

    public String getExternalUid() {
        return externalUid;
    }

    void setHandle(String providerId, String externalName, String externalUid) {
        this.providerId = providerId;
        this.externalName = externalName;
        this.externalUid = externalUid;
    }

    public Instant getLastHeartbeatAt() {
        return lastHeartbeatAt;
    }

    void setLastHeartbeatAt(Instant lastHeartbeatAt) {
        this.lastHeartbeatAt = lastHeartbeatAt;
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

    public Integer getExitCode() {
        return exitCode;
    }

    void setExitCode(Integer exitCode) {
        this.exitCode = exitCode;
    }

    public RunFailureCode getFailureCode() {
        return failureCode;
    }

    void setFailureCode(RunFailureCode failureCode) {
        this.failureCode = failureCode;
    }

    public long getLockVersion() {
        return lockVersion;
    }
}
