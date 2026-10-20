package com.perfplatform.persistence.recorder;

import com.perfplatform.domain.recorder.RecorderAuditEventType;
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

/** Storage row for one append-only recorder audit event. */
@Entity
@Table(name = "recorder_audit_events")
public class RecorderAuditEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "sequence", nullable = false, updatable = false)
    private Long sequence;

    @Column(name = "session_id", nullable = false, updatable = false)
    private UUID sessionId;

    @Column(name = "team_id", nullable = false, updatable = false)
    private UUID teamId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, updatable = false, length = 48)
    private RecorderAuditEventType eventType;

    @Column(name = "actor", nullable = false, updatable = false)
    private String actor;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detail", nullable = false, updatable = false)
    private String detail;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected RecorderAuditEventEntity() {
        // Required by JPA.
    }

    RecorderAuditEventEntity(UUID sessionId, UUID teamId, RecorderAuditEventType eventType,
            String actor, String detail, Instant occurredAt) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.teamId = Objects.requireNonNull(teamId, "teamId");
        this.eventType = Objects.requireNonNull(eventType, "eventType");
        this.actor = Objects.requireNonNull(actor, "actor");
        this.detail = Objects.requireNonNull(detail, "detail");
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
    }

    public Long getSequence() {
        return sequence;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public UUID getTeamId() {
        return teamId;
    }

    public RecorderAuditEventType getEventType() {
        return eventType;
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
