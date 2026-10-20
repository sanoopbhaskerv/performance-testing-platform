package com.perfplatform.persistence.artifact;

import com.perfplatform.domain.results.ObjectiveOutcome.Verdict;
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
 * Storage row for one objective's verdict.
 *
 * <p>Values are scaled integers in the threshold's own units — milliseconds, or hundredths of a
 * percent. Not floating point: the comparison between observed and threshold <em>is</em> the
 * verdict, and one that could differ in the last bit between two runs of the same analysis would
 * not be a deterministic answer to "did this pass".
 */
@Entity
@Table(name = "objective_results")
public class ObjectiveResultEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "run_id", nullable = false, updatable = false)
    private UUID runId;

    /**
     * Derived from metric, scope and percentile rather than from position, so the same objective
     * keeps its identifier when another is added above it — otherwise comparing two runs lines up
     * different objectives and reports a regression that did not happen.
     */
    @Column(name = "objective_id", nullable = false, updatable = false)
    private String objectiveId;

    @Column(name = "metric", nullable = false, updatable = false, length = 128)
    private String metric;

    @Column(name = "scope", nullable = false, updatable = false, length = 128)
    private String scope;

    @Enumerated(EnumType.STRING)
    @Column(name = "verdict", nullable = false, length = 16)
    private Verdict verdict;

    @Column(name = "observed_value", nullable = false)
    private long observedValue;

    @Column(name = "threshold", nullable = false)
    private long threshold;

    @Column(name = "detail", nullable = false, length = 1000)
    private String detail;

    @Column(name = "evaluated_at", nullable = false)
    private Instant evaluatedAt;

    protected ObjectiveResultEntity() {
        // Required by JPA.
    }

    ObjectiveResultEntity(UUID id, UUID runId, String objectiveId, String metric, String scope,
            Verdict verdict, long observedValue, long threshold, String detail, Instant evaluatedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.runId = Objects.requireNonNull(runId, "runId");
        this.objectiveId = Objects.requireNonNull(objectiveId, "objectiveId");
        this.metric = Objects.requireNonNull(metric, "metric");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.verdict = Objects.requireNonNull(verdict, "verdict");
        this.observedValue = observedValue;
        this.threshold = threshold;
        this.detail = detail == null ? "" : truncate(detail);
        this.evaluatedAt = Objects.requireNonNull(evaluatedAt, "evaluatedAt");
    }

    /** Detail is explanatory, so a long one is trimmed rather than failing the write. */
    private static String truncate(String detail) {
        return detail.length() <= 1000 ? detail : detail.substring(0, 997) + "...";
    }

    public UUID getId() {
        return id;
    }

    public UUID getRunId() {
        return runId;
    }

    public String getObjectiveId() {
        return objectiveId;
    }

    public String getMetric() {
        return metric;
    }

    public String getScope() {
        return scope;
    }

    public Verdict getVerdict() {
        return verdict;
    }

    public long getObservedValue() {
        return observedValue;
    }

    public long getThreshold() {
        return threshold;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getEvaluatedAt() {
        return evaluatedAt;
    }
}
