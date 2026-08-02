package com.perfplatform.domain.identity;

import java.util.UUID;

/**
 * Identifies one stored, immutable version of a dataset.
 *
 * <p>A dataset is not a run artifact: it is uploaded once and read by many runs, so it has an
 * identity of its own rather than being addressed through the run that happened to use it.
 *
 * @param value the underlying UUID, never {@code null}
 */
public record DatasetId(UUID value) implements ResourceId {

    public DatasetId {
        value = ResourceId.require(value, "DatasetId");
    }

    /** Parses a DatasetId from its external string form. */
    public static DatasetId of(String raw) {
        return new DatasetId(ResourceId.parse(raw, "DatasetId"));
    }

    /** Wraps an existing UUID as a DatasetId. */
    public static DatasetId of(UUID value) {
        return new DatasetId(value);
    }

    /** Allocates a new, random DatasetId. */
    public static DatasetId generate() {
        return new DatasetId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
