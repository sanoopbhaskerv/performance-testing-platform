package com.perfplatform.api.v2.dto;

import com.perfplatform.domain.port.ArtifactStore.ArtifactReference;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * A stored artifact, by reference.
 *
 * <p>No URL. A pre-signed URL returned here would be one the caller could keep, share and use after
 * their access was revoked; a download is a separate, authorized request each time (CLAUDE.md §17).
 * The digest is included so a caller can verify what they eventually download is what was recorded.
 */
@Schema(description = "A durable reference to one of a run's artifacts")
public record ArtifactResponse(
        String id,
        String type,
        String objectKey,
        String digest,
        String contentType,
        long sizeBytes,
        Instant storedAt) {

    public static ArtifactResponse from(ArtifactReference reference) {
        return new ArtifactResponse(
                reference.id().toString(),
                reference.type().name(),
                reference.objectKey(),
                reference.digest(),
                reference.contentType(),
                reference.sizeBytes(),
                reference.storedAt());
    }
}
