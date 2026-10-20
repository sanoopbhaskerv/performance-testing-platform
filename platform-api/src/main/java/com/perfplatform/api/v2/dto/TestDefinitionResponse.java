package com.perfplatform.api.v2.dto;

import com.perfplatform.domain.port.TestDefinitionRepository.StoredVersion;
import com.perfplatform.domain.port.TestDefinitionRepository.TestDefinitionSummary;
import com.perfplatform.domain.validation.ValidationFinding;
import com.perfplatform.domain.validation.ValidationResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * A definition and, when one was just created, the version that came with it.
 *
 * @param id            definition identifier
 * @param teamId        owning team
 * @param applicationId application under test
 * @param name          definition name, taken from {@code metadata.name}
 * @param latestVersion highest version number, 0 for a definition with none
 * @param createdBy     who authored it
 * @param createdAt     when
 * @param version       the version this request produced, absent on a plain read
 * @param findings      validation findings for that version, so a caller who stored an invalid
 *                      specification learns why in the same response rather than having to ask
 */
@Schema(description = "A test definition and its latest version")
public record TestDefinitionResponse(
        String id,
        String teamId,
        String applicationId,
        String name,
        int latestVersion,
        String createdBy,
        Instant createdAt,
        VersionSummary version,
        List<ValidationFindingResponse> findings) {

    /**
     * @param id            version identifier — this is what a run references
     * @param versionNumber contiguous within the definition
     * @param digest        digest of the canonical specification
     * @param executable    whether this version may be run; false when validation failed
     */
    @Schema(description = "One immutable version of a definition")
    public record VersionSummary(
            String id,
            int versionNumber,
            String digest,
            boolean executable,
            String createdBy,
            Instant createdAt) {

        public static VersionSummary from(StoredVersion version) {
            return new VersionSummary(
                    version.id().toString(),
                    version.versionNumber(),
                    version.digest().value(),
                    version.valid(),
                    version.createdBy(),
                    version.createdAt());
        }
    }

    /** One validation finding, matching the shape the validate endpoint already returns. */
    @Schema(description = "A single validation finding")
    public record ValidationFindingResponse(String code, String severity, String path,
            String message, String remediation) {

        static ValidationFindingResponse from(ValidationFinding finding) {
            return new ValidationFindingResponse(
                    finding.code(),
                    finding.severity().name(),
                    finding.path(),
                    finding.message(),
                    finding.remediation());
        }
    }

    public static TestDefinitionResponse of(TestDefinitionSummary definition) {
        return of(definition, null, ValidationResult.valid());
    }

    public static TestDefinitionResponse of(TestDefinitionSummary definition, StoredVersion version,
            ValidationResult findings) {
        return new TestDefinitionResponse(
                definition.id().toString(),
                definition.teamId().toString(),
                definition.applicationId().toString(),
                definition.name(),
                definition.latestVersion(),
                definition.createdBy(),
                definition.createdAt(),
                version == null ? null : VersionSummary.from(version),
                findings.findings().stream().map(ValidationFindingResponse::from).toList());
    }
}
