package com.perfplatform.persistence.run;

import com.perfplatform.domain.run.RunEventType;
import com.perfplatform.domain.run.RunState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Storage row for one entry in a run's history.
 *
 * <p>Append-only. There are no setters and no update path: an event is inserted and never touched
 * again. PostgreSQL enforces the same rule with a trigger, which is the half that survives a direct
 * SQL write — an audit trail an operator can quietly edit is not an audit trail.
 *
 * <p>The sequence is database-generated rather than application-assigned. Two control-plane replicas
 * appending events for the same run must produce a total order, and only the database can allocate
 * one without coordination between them.
 */
@Entity
@Table(name = "run_events")
public class RunEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "sequence", nullable = false, updatable = false)
    private Long sequence;

    @Column(name = "run_id", nullable = false, updatable = false)
    private UUID runId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, updatable = false, length = 48)
    private RunEventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_state", updatable = false, length = 32)
    private RunState previousState;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_state", updatable = false, length = 32)
    private RunState newState;

    @Column(name = "actor", nullable = false, updatable = false)
    private String actor;

    /**
     * Sanitized key/value context as a flat JSON object.
     *
     * <p>Held as a String rather than a Map so that no JSON binding leaks into this module — the
     * domain renders and parses it ({@code RunEventDetail}), and the column is JSONB so an operator
     * can query inside it.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detail", nullable = false, updatable = false)
    private String detail;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected RunEventEntity() {
        // Required by JPA.
    }

    RunEventEntity(UUID runId, RunEventType eventType, RunState previousState, RunState newState,
            String actor, String detail, Instant occurredAt) {
        this.runId = Objects.requireNonNull(runId, "runId");
        this.eventType = Objects.requireNonNull(eventType, "eventType");
        this.previousState = previousState;
        this.newState = newState;
        this.actor = Objects.requireNonNull(actor, "actor");
        this.detail = Objects.requireNonNull(detail, "detail");
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
    }

    public Long getSequence() {
        return sequence;
    }

    public UUID getRunId() {
        return runId;
    }

    public RunEventType getEventType() {
        return eventType;
    }

    public RunState getPreviousState() {
        return previousState;
    }

    public RunState getNewState() {
        return newState;
    }

    public String getActor() {
        return actor;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
