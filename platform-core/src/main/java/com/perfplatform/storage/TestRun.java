package com.perfplatform.storage;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Persists each test run for history and reporting.
 */
@Entity
@Table(name = "test_runs")
public class TestRun {

    public enum Status {
        PENDING, RUNNING, COMPLETED, FAILED
    }

    public enum SlaResult {
        PASS, BREACH, UNKNOWN
    }

    public enum TestType {
        LOAD, ENDURANCE, SPIKE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String team;

    @Enumerated(EnumType.STRING)
    private TestType testType;

    @Enumerated(EnumType.STRING)
    private Status status = Status.PENDING;

    @Enumerated(EnumType.STRING)
    private SlaResult slaResult = SlaResult.UNKNOWN;

    private Instant startedAt;
    private Instant completedAt;

    // SLA metrics captured after test
    private Long p95LatencyMs;
    private Double errorRatePercent;
    private Integer peakHeapUsagePercent;

    // Path to the generated HTML report file
    private String reportPath;

    // Human-readable failure reason if status=FAILED
    @Column(length = 2000)
    private String failureReason;

    // Heap dump report path (null if analysis.heapDump.enabled=false)
    private String heapDumpReportPath;

    // Thread dump report path (null if analysis.threadDump.enabled=false)
    private String threadDumpReportPath;

    @Column(updatable = false)
    private Instant createdAt = Instant.now();

    // ── Getters / Setters ────────────────────────────────────────────────

    public String getId() {
        return id;
    }

    public String getTeam() {
        return team;
    }

    public void setTeam(String team) {
        this.team = team;
    }

    public TestType getTestType() {
        return testType;
    }

    public void setTestType(TestType testType) {
        this.testType = testType;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public SlaResult getSlaResult() {
        return slaResult;
    }

    public void setSlaResult(SlaResult slaResult) {
        this.slaResult = slaResult;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public Long getP95LatencyMs() {
        return p95LatencyMs;
    }

    public void setP95LatencyMs(Long p95LatencyMs) {
        this.p95LatencyMs = p95LatencyMs;
    }

    public Double getErrorRatePercent() {
        return errorRatePercent;
    }

    public void setErrorRatePercent(Double errorRatePercent) {
        this.errorRatePercent = errorRatePercent;
    }

    public Integer getPeakHeapUsagePercent() {
        return peakHeapUsagePercent;
    }

    public void setPeakHeapUsagePercent(Integer peakHeapUsagePercent) {
        this.peakHeapUsagePercent = peakHeapUsagePercent;
    }

    public String getReportPath() {
        return reportPath;
    }

    public void setReportPath(String reportPath) {
        this.reportPath = reportPath;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public String getHeapDumpReportPath() {
        return heapDumpReportPath;
    }

    public void setHeapDumpReportPath(String heapDumpReportPath) {
        this.heapDumpReportPath = heapDumpReportPath;
    }

    public String getThreadDumpReportPath() {
        return threadDumpReportPath;
    }

    public void setThreadDumpReportPath(String threadDumpReportPath) {
        this.threadDumpReportPath = threadDumpReportPath;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
