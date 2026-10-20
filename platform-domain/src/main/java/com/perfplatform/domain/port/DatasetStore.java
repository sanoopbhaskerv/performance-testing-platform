package com.perfplatform.domain.port;

import com.perfplatform.domain.dataset.DatasetUri;
import com.perfplatform.domain.identity.TeamId;

import java.util.Objects;
import java.util.Optional;

/**
 * Durable storage for the data a test sends, as opposed to the files a run produces.
 *
 * <h2>Why this is a sibling of {@link ArtifactStore} rather than more methods on it</h2>
 * {@code ArtifactStore} is run-scoped to its foundations: an {@code ArtifactDescriptor}
 * <em>requires</em> a {@code TestRunId}, and its key layout puts every object under
 * {@code runs/{runId}/}. That is not an accident — it is what makes a run's evidence retrievable,
 * authorized and reclaimable as one unit.
 *
 * <p>A dataset has none of those properties. It is uploaded before any run exists, read by many
 * runs, and outlives every one of them. Widening the artifact port would mean either a nullable
 * run identifier on the descriptor — which would quietly weaken the invariant that every artifact
 * belongs to a run — or a second key layout inside a class whose javadoc promises one. So: a second
 * port, deliberately small, with the <em>same</em> team-first key layout.
 *
 * <p>{@code teams/{teamId}/datasets/{name}/{version}/data.csv}. The team prefix first, for the same
 * reason as artifacts: authorization is a property of the key, so a caller who may not read a team's
 * data cannot construct a key that reaches it.
 *
 * <h2>Immutable by version</h2>
 * Storing over an existing version is refused by the catalog, not tolerated here. A test pinned to
 * {@code dataset://orders/3} must read the same rows in a year as it did today, or a comparison
 * between two runs of one definition version is comparing two different tests.
 */
public interface DatasetStore {

    /**
     * What a stored dataset version is, as far as anything outside storage is concerned.
     *
     * @param objectKey  stable storage key, never a URL and never a filesystem path
     * @param digest     SHA-256 over the content, as {@code sha256:<hex>}
     * @param sizeBytes  size as stored
     */
    record StoredContent(String objectKey, String digest, long sizeBytes) {

        public StoredContent {
            Objects.requireNonNull(digest, "digest");
            if (objectKey == null || objectKey.isBlank()) {
                throw new IllegalArgumentException("objectKey must not be null or blank");
            }
            if (sizeBytes < 0) {
                throw new IllegalArgumentException("sizeBytes must not be negative");
            }
        }
    }

    /** Stores one version's content and returns its durable reference. */
    StoredContent store(TeamId teamId, DatasetUri uri, byte[] content);

    /**
     * Reads a dataset by key.
     *
     * <p>By key rather than by name and version, because the caller has already resolved a reference
     * through the catalog. Two paths to the same object would be two places for the key layout to be
     * decided, and the layout is what makes authorization structural.
     *
     * <p>Empty when the object is gone — retention and deletion are ordinary, and the caller reports
     * a missing dataset honestly rather than a storage adapter throwing.
     */
    Optional<byte[]> read(String objectKey);
}
