package com.perfplatform.domain.port;

import com.perfplatform.domain.dataset.DatasetUri;
import com.perfplatform.domain.identity.DatasetId;
import com.perfplatform.domain.identity.TeamId;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The durable record of which dataset versions exist.
 *
 * <p>The same split as {@link ArtifactStore} and {@link ResultCatalog}: that stores content, this
 * stores the record of it. In production they are different systems — object storage and PostgreSQL
 * — with different sizes, lifetimes and failure modes.
 *
 * <p>It carries what a key cannot: the column names the file turned out to have, how many rows it
 * has, who uploaded it and when. The columns matter most. A specification <em>declares</em> the
 * columns it expects, and a run compares the two before generating any load, so a re-exported
 * dataset cannot silently bind different values to the same names.
 */
public interface DatasetCatalog {

    /**
     * One immutable version of a dataset.
     *
     * @param uri        how a specification refers to this version
     * @param objectKey  where the content lives, as a stable key
     * @param digest     SHA-256 over the content
     * @param columns    the header the file actually has, in file order
     * @param rowCount   data rows, excluding the header
     * @param uploadedBy the subject who uploaded it, so the provenance of what a test sends is part
     *                   of the record rather than only of a log
     */
    record DatasetVersion(
            DatasetId id,
            TeamId teamId,
            DatasetUri uri,
            String objectKey,
            String digest,
            List<String> columns,
            long rowCount,
            long sizeBytes,
            String uploadedBy,
            Instant uploadedAt) {

        public DatasetVersion {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(teamId, "teamId");
            Objects.requireNonNull(uri, "uri");
            Objects.requireNonNull(uploadedAt, "uploadedAt");
            columns = List.copyOf(columns);
        }

        public String name() {
            return uri.name();
        }

        public int version() {
            return uri.version();
        }
    }

    /**
     * Raised when a version already exists.
     *
     * <p>Its own type because the answer to it is specific: upload the next version. Overwriting is
     * never the right behaviour — a test pinned to version 3 would start reading different rows with
     * nothing in its own history saying so.
     */
    class DatasetVersionExistsException extends RuntimeException {
        public DatasetVersionExistsException(DatasetUri uri) {
            super("Dataset version " + uri + " already exists and datasets are immutable. "
                    + "Upload the next version instead.");
        }
    }

    /**
     * Records a stored version.
     *
     * @throws DatasetVersionExistsException if that name and version is already recorded. Enforced
     *                                       by a unique constraint rather than a prior read, so two
     *                                       concurrent uploads cannot both believe they are creating
     *                                       the same version
     */
    DatasetVersion register(DatasetVersion version);

    /** One version, if the team has it. */
    Optional<DatasetVersion> find(TeamId teamId, String name, int version);

    /** Every version a team has, newest first. */
    List<DatasetVersion> listForTeam(TeamId teamId);

    /**
     * The highest version number a team has for a name, or {@code 0} when it has none.
     *
     * <p>A hint for allocating the next one. It is deliberately not what resolution uses: a run
     * resolves the exact version its specification pins, never "the latest", because the latest
     * changes without the specification changing.
     */
    int latestVersion(TeamId teamId, String name);
}
