package com.perfplatform.domain.port;

import com.perfplatform.domain.identity.ArtifactId;
import com.perfplatform.domain.identity.RunnerShardId;
import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.identity.TestRunId;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Durable storage for the files a run produces and consumes.
 *
 * <p>This exists because of a specific V1 defect: V1 persisted local filesystem report paths as if
 * they were durable references. A path inside a pod is not a reference to anything once the pod is
 * gone, so V1's run history accumulated links to reports nobody could ever open again.
 *
 * <p>Everything here is content-addressed and team-prefixed. The key form is
 * {@code teams/{teamId}/runs/{runId}/...}, which makes authorization a property of the key rather
 * than a lookup: a caller who may not read a team's data cannot construct a key that would reach it.
 *
 * <h2>What the database stores</h2>
 * A stable reference — the key, a digest, a content type, a size. Never a pre-signed URL: those
 * expire, so a stored one is a reference that works until it silently does not.
 */
public interface ArtifactStore {

    /**
     * What a stored dataset partition's file name begins with.
     *
     * <p>Part of the key contract, not a naming convention. A dataset partition is a CSV, and so is
     * a JMeter results file — without something in the name to tell them apart, an input would be
     * adopted as a result and its rows parsed as measurements. The engine still receives the
     * partition under its own plain name; only the stored object carries this prefix.
     */
    String DATASET_OBJECT_PREFIX = "dataset-";

    /** What kind of thing an artifact is. Drives retention and what a report links to. */
    enum ArtifactType {
        /** The rendered workload a shard executed — the evidence of what actually ran. */
        EXECUTION_PLAN,
        /** Raw engine results, e.g. a JTL file. */
        RESULTS,
        /** Runner logs. */
        RUNNER_LOG,
        /** The runner's self-description, used to verify identity before results are accepted. */
        RUNNER_MANIFEST,
        /**
         * The slice of a dataset one shard sent.
         *
         * <p>An input rather than an output, and stored as an artifact for the same reason the
         * rendered plan is: a result is only reproducible if what produced it is recoverable. It
         * also answers, months later, which rows a particular shard actually used — a question that
         * otherwise has no answer once the pod is gone.
         */
        DATASET,
        /** A generated human-readable report. */
        REPORT,
        /**
         * A runner's rolling account of itself while it is still executing.
         *
         * <p>Overwritten in place every few seconds under one key per shard, so the store holds the
         * latest rather than a growing pile — the merged history lives in the database, where it can
         * be queried. Unlike every other type here it is <em>not</em> evidence of a result: it is
         * reduced from partial output with bucketed percentiles, and a run's verdict is never
         * computed from it. It is kept after a run ends anyway, because a run that died leaves this
         * as the only account of what was happening at the time.
         */
        PROGRESS
    }

    /**
     * A stored artifact.
     *
     * @param id          artifact identifier
     * @param runId       the run it belongs to
     * @param shardId     the shard that produced it, absent for run-level artifacts
     * @param type        what it is
     * @param objectKey   stable storage key, not a URL
     * @param digest      SHA-256 over the content, as {@code sha256:<hex>}
     * @param contentType MIME type
     * @param sizeBytes   size as stored
     * @param storedAt    when it was written
     */
    record ArtifactReference(
            ArtifactId id,
            TestRunId runId,
            Optional<RunnerShardId> shardId,
            ArtifactType type,
            String objectKey,
            String digest,
            String contentType,
            long sizeBytes,
            Instant storedAt) {

        public ArtifactReference {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(storedAt, "storedAt");
            shardId = shardId == null ? Optional.empty() : shardId;
            if (objectKey == null || objectKey.isBlank()) {
                throw new IllegalArgumentException("objectKey must not be null or blank");
            }
            if (sizeBytes < 0) {
                throw new IllegalArgumentException("sizeBytes must not be negative");
            }
        }
    }

    /**
     * What to store.
     *
     * @param teamId   the owning team, which prefixes the key
     * @param fileName the name to store under, within the run's prefix
     */
    record ArtifactDescriptor(
            TeamId teamId,
            TestRunId runId,
            Optional<RunnerShardId> shardId,
            ArtifactType type,
            String fileName,
            String contentType) {

        public ArtifactDescriptor {
            Objects.requireNonNull(teamId, "teamId");
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(type, "type");
            shardId = shardId == null ? Optional.empty() : shardId;
            if (fileName == null || fileName.isBlank()) {
                throw new IllegalArgumentException("fileName must not be null or blank");
            }
            // A file name is the last path segment and nothing more. A name containing a separator
            // or a parent reference could escape the run's prefix, which is what makes the key
            // prefix an authorization boundary rather than a convention.
            if (fileName.contains("/") || fileName.contains("\\") || fileName.contains("..")) {
                throw new IllegalArgumentException(
                        "fileName must be a plain file name, not a path: '" + fileName + "'");
            }
            contentType = contentType == null || contentType.isBlank()
                    ? "application/octet-stream" : contentType;
        }
    }

    /** Raised when an artifact cannot be stored or read. */
    class ArtifactStoreException extends RuntimeException {
        public ArtifactStoreException(String message, Throwable cause) {
            super(message, cause);
        }

        public ArtifactStoreException(String message) {
            super(message);
        }
    }

    /**
     * Stores content and returns its durable reference.
     *
     * <p>Writes are idempotent by key: storing the same descriptor twice with the same content is a
     * no-op rather than an error, because a reconciliation pass that repeats must not fail.
     */
    ArtifactReference store(ArtifactDescriptor descriptor, byte[] content);

    /**
     * Reads an artifact's content.
     *
     * <p>Present so analysis can be re-run from stored artifacts months later, which is what makes
     * an objective verdict re-derivable rather than something taken on trust.
     */
    Optional<byte[]> read(ArtifactReference reference);

    /**
     * Deletes the stored object, if it is still present.
     *
     * <p>Idempotent by reference: retention cleanup, repeated reconciliation and object-store
     * lifecycle policies may all race. A missing object is therefore success, while the catalog row
     * can remain as durable evidence that the run produced the artifact and why a later report can
     * no longer read it.
     */
    void delete(ArtifactReference reference);

    /** Every artifact of a run, oldest first. */
    List<ArtifactReference> listForRun(TestRunId runId);
}
