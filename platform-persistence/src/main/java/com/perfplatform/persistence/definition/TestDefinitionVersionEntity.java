package com.perfplatform.persistence.definition;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One immutable version of a test definition.
 *
 * <p>Nothing updates the specification of a stored version. Reproducing a run months later
 * requires the exact specification that ran, and a mutable version would silently invalidate
 * every run and approval that referenced it.
 *
 * <p>Immutability is enforced at three levels, because any one of them alone is bypassable:
 * <ol>
 *   <li>{@link TestDefinitionRegistry} refuses to persist over an existing version;</li>
 *   <li>the content columns here are {@code updatable = false}, so JPA never emits an UPDATE
 *       for them even if application code mutates the object;</li>
 *   <li>a database trigger rejects the UPDATE outright, which is the only level that survives a
 *       direct SQL write by an operator or another service.</li>
 * </ol>
 *
 * <p>Approval fields are the one permitted change: approving a version records a decision
 * <em>about</em> it without altering what it says.
 *
 * <p>This entity is never returned from the API. Response DTOs live in {@code platform-api}, so
 * the storage schema does not become the public contract — the defect V1 has.
 */
@Entity
@Table(name = "test_definition_versions")
public class TestDefinitionVersionEntity {

    /** Whether the specification passed validation when this version was created. */
    public enum ValidationStatus {
        VALID,
        INVALID
    }

    /** Whether this version needs, or has received, approval before it may execute. */
    public enum ApprovalStatus {
        NOT_REQUIRED,
        PENDING,
        APPROVED,
        REJECTED
    }

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "definition_id", nullable = false, updatable = false)
    private UUID definitionId;

    @Column(name = "version_number", nullable = false, updatable = false)
    private int versionNumber;

    /**
     * The canonical specification as submitted. Stored as JSONB on PostgreSQL and H2's JSON type
     * locally, but held here as a String: the domain model must not acquire a JSON type binding,
     * and the stored text must round-trip byte-for-byte because its digest is taken over it.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "specification_json", nullable = false, updatable = false)
    private String specificationJson;

    /**
     * SHA-256 over the canonical form, as {@code sha256:<hex>}. A run references this, and runner
     * results whose plan digest does not match are rejected rather than merged.
     */
    @Column(name = "specification_digest", nullable = false, updatable = false, length = 80)
    private String specificationDigest;

    @Enumerated(EnumType.STRING)
    @Column(name = "validation_status", nullable = false, updatable = false, length = 32)
    private ValidationStatus validationStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "approval_status", nullable = false, length = 32)
    private ApprovalStatus approvalStatus = ApprovalStatus.NOT_REQUIRED;

    @Column(name = "created_by", nullable = false, updatable = false)
    private String createdBy;

    @Column(name = "approved_by")
    private String approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected TestDefinitionVersionEntity() {
        // Required by JPA.
    }

    public TestDefinitionVersionEntity(UUID id, UUID definitionId, int versionNumber,
            String specificationJson, String specificationDigest,
            ValidationStatus validationStatus, String createdBy, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.definitionId = Objects.requireNonNull(definitionId, "definitionId");
        if (versionNumber <= 0) {
            throw new IllegalArgumentException("versionNumber must be positive");
        }
        this.versionNumber = versionNumber;
        this.specificationJson = Objects.requireNonNull(specificationJson, "specificationJson");
        this.specificationDigest = Objects.requireNonNull(specificationDigest, "specificationDigest");
        this.validationStatus = Objects.requireNonNull(validationStatus, "validationStatus");
        this.createdBy = Objects.requireNonNull(createdBy, "createdBy");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    /**
     * Records an approval decision. This is the only permitted mutation: it says something about
     * the version without changing what the version is.
     */
    public void approve(String approver, Instant approvedAt) {
        this.approvalStatus = ApprovalStatus.APPROVED;
        this.approvedBy = Objects.requireNonNull(approver, "approver");
        this.approvedAt = Objects.requireNonNull(approvedAt, "approvedAt");
    }

    public void reject(String approver, Instant decidedAt) {
        this.approvalStatus = ApprovalStatus.REJECTED;
        this.approvedBy = Objects.requireNonNull(approver, "approver");
        this.approvedAt = Objects.requireNonNull(decidedAt, "decidedAt");
    }

    public void requireApproval() {
        this.approvalStatus = ApprovalStatus.PENDING;
    }

    public UUID getId() {
        return id;
    }

    public UUID getDefinitionId() {
        return definitionId;
    }

    public int getVersionNumber() {
        return versionNumber;
    }

    public String getSpecificationJson() {
        return specificationJson;
    }

    public String getSpecificationDigest() {
        return specificationDigest;
    }

    public ValidationStatus getValidationStatus() {
        return validationStatus;
    }

    public ApprovalStatus getApprovalStatus() {
        return approvalStatus;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public String getApprovedBy() {
        return approvedBy;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
