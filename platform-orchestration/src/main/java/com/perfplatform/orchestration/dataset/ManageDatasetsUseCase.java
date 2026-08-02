package com.perfplatform.orchestration.dataset;

import com.perfplatform.domain.dataset.DatasetDocument;
import com.perfplatform.domain.dataset.DatasetUri;
import com.perfplatform.domain.identity.DatasetId;
import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.port.DatasetCatalog;
import com.perfplatform.domain.port.DatasetCatalog.DatasetVersion;
import com.perfplatform.domain.port.DatasetStore;
import com.perfplatform.domain.port.TeamDirectory;
import com.perfplatform.domain.security.CallerContext;
import com.perfplatform.domain.security.Scope;
import com.perfplatform.domain.validation.ValidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Uploading, listing and reading the data a test sends.
 *
 * <p>Increment 2g made a dataset declarable, bindable and checkable. This is what makes one arrive.
 *
 * <h2>Authorized by team, like everything else</h2>
 * {@code definitions:write} for the owning team, because a dataset is part of what a test <em>is</em>
 * — someone who can change the rows a test sends can change what it exercises as surely as someone
 * who can edit its journeys. Reading takes {@code definitions:read} for the same reason: a dataset
 * is one team's business data and no other team's business.
 *
 * <h2>Immutable, and versioned by the platform</h2>
 * The uploader names a dataset, never a version. Version numbers are allocated here, one past the
 * highest that exists, so nothing can overwrite a version a specification is pinned to. Pointing a
 * test at new data is an edit to the specification, which is a reviewable act with its own digest.
 */
public final class ManageDatasetsUseCase {

    private static final Logger log = LoggerFactory.getLogger(ManageDatasetsUseCase.class);

    /**
     * Names take the restricted form every name in a specification takes.
     *
     * <p>A dataset name reaches an object key, and a key is what makes authorization structural.
     */
    private static final Pattern NAME = Pattern.compile("^[a-z0-9]([-a-z0-9]*[a-z0-9])?$");

    private final DatasetStore store;
    private final DatasetCatalog catalog;
    private final TeamDirectory teams;
    private final java.time.Clock clock;
    private final long maxSizeBytes;

    /**
     * @param maxSizeBytes the largest upload accepted. Capped because the control plane reads the
     *                     whole file to slice it — the trade the platform makes in exchange for
     *                     partitions being auditable stored artifacts rather than something a
     *                     runner decided in private
     */
    public ManageDatasetsUseCase(DatasetStore store, DatasetCatalog catalog, TeamDirectory teams,
            java.time.Clock clock, long maxSizeBytes) {
        this.store = Objects.requireNonNull(store, "store");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.teams = Objects.requireNonNull(teams, "teams");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maxSizeBytes < 1) {
            throw new IllegalArgumentException("maxSizeBytes must be positive");
        }
        this.maxSizeBytes = maxSizeBytes;
    }

    /** What an upload produced: the stored version, and everything worth telling the uploader. */
    public record UploadResult(DatasetVersion version, ValidationResult findings) {
    }

    /** Raised when an upload cannot be accepted. Carries findings so the reason is specific. */
    public static class DatasetRejectedException extends RuntimeException {
        private final transient ValidationResult findings;

        public DatasetRejectedException(String message, ValidationResult findings) {
            super(message);
            this.findings = findings;
        }

        public ValidationResult findings() {
            return findings;
        }
    }

    /** Raised when a dataset name or version does not resolve for the caller's team. */
    public static class UnknownDatasetException extends RuntimeException {
        public UnknownDatasetException(String name, int version) {
            super("No dataset '" + name + "' version " + version + " is registered for this team.");
        }
    }

    /** Raised when the named team is not registered. */
    public static class UnknownTeamException extends RuntimeException {
        public UnknownTeamException(String teamName) {
            super("No team named '" + teamName + "' is registered.");
        }
    }

    /**
     * Stores the next version of a dataset.
     *
     * <p>Order matters: the document is judged first, then content is written, then the record. A
     * record without content would be a dataset a run resolves and cannot read; content without a
     * record is an orphaned object that nothing refers to and retention reclaims. The second is the
     * survivable failure, so it is the one this risks.
     *
     * @throws DatasetRejectedException if the name, size or contents are not acceptable
     */
    public UploadResult upload(String teamName, String name, byte[] content, CallerContext caller) {
        TeamId teamId = teams.findByName(teamName)
                .orElseThrow(() -> new UnknownTeamException(teamName));
        caller.requireAct(Scope.DEFINITIONS_WRITE, teamId);

        requireValidName(name);
        requireAcceptableSize(content);

        String csv = new String(content, StandardCharsets.UTF_8);
        DatasetDocument.Inspection inspection = DatasetDocument.inspect(csv);
        if (!inspection.isAcceptable()) {
            // Nothing has been written at this point, and the message says so. "Rejected" that left
            // a half-stored file would be worse than no check at all.
            throw new DatasetRejectedException(
                    "The uploaded dataset was not stored.", inspection.findings());
        }

        DatasetUri uri = DatasetUri.of(name, catalog.latestVersion(teamId, name) + 1);
        DatasetStore.StoredContent stored = store.store(teamId, uri, content);

        DatasetVersion version = catalog.register(new DatasetVersion(
                DatasetId.generate(), teamId, uri, stored.objectKey(), stored.digest(),
                inspection.columns(), inspection.rowCount(), stored.sizeBytes(),
                caller.subject(), clock.instant()));

        // Column names, never a value: this line reaches a log aggregator.
        log.info("Dataset stored teamId={} dataset={} version={} rows={} bytes={} columns={} "
                        + "subject={}",
                teamId, uri.name(), uri.version(), inspection.rowCount(), stored.sizeBytes(),
                inspection.columns(), caller.subject());

        return new UploadResult(version, inspection.findings());
    }

    /** Every dataset version the caller's teams hold, newest first within each name. */
    public List<DatasetVersion> listForCaller(CallerContext caller) {
        List<DatasetVersion> visible = new java.util.ArrayList<>();
        caller.teamIds().stream()
                .filter(teamId -> caller.canAct(Scope.DEFINITIONS_READ, teamId))
                .forEach(teamId -> visible.addAll(catalog.listForTeam(teamId)));
        return List.copyOf(visible);
    }

    /** One version's record, authorized against the team that owns it. */
    public DatasetVersion get(String teamName, String name, int version, CallerContext caller) {
        TeamId teamId = teams.findByName(teamName)
                .orElseThrow(() -> new UnknownTeamException(teamName));
        caller.requireAct(Scope.DEFINITIONS_READ, teamId);

        return catalog.find(teamId, name, version)
                .orElseThrow(() -> new UnknownDatasetException(name, version));
    }

    private static void requireValidName(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new DatasetRejectedException("The dataset name is not usable.",
                    ValidationResult.of(com.perfplatform.domain.validation.ValidationFinding.error(
                            "dataset.name.invalid", "/name",
                            "'" + name + "' is not a usable dataset name.",
                            "Use lowercase letters, digits and hyphens, for example "
                                    + "proposal-clients. The name becomes part of a storage key.")));
        }
    }

    private void requireAcceptableSize(byte[] content) {
        if (content == null || content.length == 0) {
            throw new DatasetRejectedException("The upload was empty.",
                    ValidationResult.of(com.perfplatform.domain.validation.ValidationFinding.error(
                            "dataset.empty", "/", "The upload contained no data.",
                            "Send the CSV as the request body, with a header row first.")));
        }
        if (content.length > maxSizeBytes) {
            throw new DatasetRejectedException("The upload is too large.",
                    ValidationResult.of(com.perfplatform.domain.validation.ValidationFinding.error(
                            "dataset.tooLarge", "/",
                            "The upload is " + content.length + " bytes, over the limit of "
                                    + maxSizeBytes + ".",
                            "The control plane reads the whole file to slice it per shard, so the "
                                    + "size is capped. Reduce the dataset, or ask an administrator "
                                    + "to raise platform.orchestration.datasets.max-size.")));
        }
    }
}
