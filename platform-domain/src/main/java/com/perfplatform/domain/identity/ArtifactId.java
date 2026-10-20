package com.perfplatform.domain.identity;

import java.util.UUID;

/**
 * Identifies a stored artifact. The identifier refers to a stable object reference in the artifact store, never to a pod-local path or an expiring signed URL.
 *
 * @param value the underlying UUID, never {@code null}
 */
public record ArtifactId(UUID value) implements ResourceId {

    public ArtifactId {
        value = ResourceId.require(value, "ArtifactId");
    }

    /** Parses a ArtifactId from its external string form. */
    public static ArtifactId of(String raw) {
        return new ArtifactId(ResourceId.parse(raw, "ArtifactId"));
    }

    /** Wraps an existing UUID as a ArtifactId. */
    public static ArtifactId of(UUID value) {
        return new ArtifactId(value);
    }

    /** Allocates a new, random ArtifactId. */
    public static ArtifactId generate() {
        return new ArtifactId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
