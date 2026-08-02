package com.perfplatform.api.v2.dto;

import com.perfplatform.domain.port.DatasetCatalog.DatasetVersion;
import com.perfplatform.domain.validation.ValidationResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * One stored dataset version, by reference.
 *
 * <p>No rows, ever. This is business data — the whole reason it is team-scoped and authorized — and
 * an endpoint that returned a sample "just to confirm the upload worked" would put customer records
 * into a response body, a browser cache and whatever logs sit between. The column names, the row
 * count and the digest are what tell somebody the upload is what they meant to send.
 *
 * <p>{@code uri} is the value that goes into a specification. It is returned because there is
 * nowhere else to learn the version number that was just allocated.
 */
@Schema(description = "A stored, immutable dataset version")
public record DatasetResponse(
        String id,
        String name,
        int version,
        String uri,
        List<String> columns,
        long rowCount,
        long sizeBytes,
        String digest,
        String uploadedBy,
        Instant uploadedAt,
        List<FindingResponse> findings) {

    /** One thing worth telling the uploader. Same shape as a specification validation finding. */
    @Schema(description = "Something the platform noticed about the uploaded file")
    public record FindingResponse(String code, String severity, String path, String message,
            String remediation) {
    }

    public static DatasetResponse from(DatasetVersion version) {
        return from(version, ValidationResult.valid());
    }

    public static DatasetResponse from(DatasetVersion version, ValidationResult findings) {
        return new DatasetResponse(
                version.id().toString(),
                version.name(),
                version.version(),
                version.uri().toString(),
                version.columns(),
                version.rowCount(),
                version.sizeBytes(),
                version.digest(),
                version.uploadedBy(),
                version.uploadedAt(),
                findings.findings().stream()
                        .map(finding -> new FindingResponse(
                                finding.code(), finding.severity().name(), finding.path(),
                                finding.message(), finding.remediation()))
                        .toList());
    }
}
