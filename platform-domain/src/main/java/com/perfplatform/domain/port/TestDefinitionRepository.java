package com.perfplatform.domain.port;

import com.perfplatform.domain.identity.ApplicationId;
import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.identity.TestDefinitionId;
import com.perfplatform.domain.identity.TestDefinitionVersionId;
import com.perfplatform.domain.spec.SpecificationDigest;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Creates definitions and appends immutable versions.
 *
 * <p>The write side of the specification registry. {@link DefinitionVersionCatalog} is the read
 * side, and they are separate because they are used by different things for different reasons: the
 * catalog is read on every trigger and every reconciliation pass and returns a parsed
 * specification, while this is used only when someone authors one.
 *
 * <p>No update or delete. A stored version is immutable, and a definition changes only by gaining
 * a version.
 */
public interface TestDefinitionRepository {

    /**
     * A definition and where its versions have got to.
     *
     * @param latestVersion the highest version number, or 0 for a definition with no versions yet
     */
    record TestDefinitionSummary(
            TestDefinitionId id,
            TeamId teamId,
            ApplicationId applicationId,
            String name,
            int latestVersion,
            String createdBy,
            Instant createdAt) {

        public TestDefinitionSummary {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(teamId, "teamId");
            Objects.requireNonNull(applicationId, "applicationId");
            Objects.requireNonNull(createdAt, "createdAt");
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("name must not be null or blank");
            }
        }
    }

    /** A stored version, without its parsed specification. */
    record StoredVersion(
            TestDefinitionVersionId id,
            TestDefinitionId definitionId,
            int versionNumber,
            SpecificationDigest digest,
            boolean valid,
            String createdBy,
            Instant createdAt) {
    }

    /** Raised when a team already owns a definition of this name. */
    class DuplicateDefinitionException extends RuntimeException {
        public DuplicateDefinitionException(String name) {
            super("This team already owns a test definition named '" + name
                    + "'. Append a version to it instead, or choose another name.");
        }
    }

    Optional<TestDefinitionSummary> find(TestDefinitionId id);

    Optional<TestDefinitionSummary> findByName(TeamId teamId, String name);

    List<TestDefinitionSummary> listForTeam(TeamId teamId);

    List<StoredVersion> listVersions(TestDefinitionId definitionId);

    /**
     * Creates a definition with no versions.
     *
     * @throws DuplicateDefinitionException if the team already owns a definition of this name
     */
    TestDefinitionSummary create(TeamId teamId, ApplicationId applicationId, String name,
            String createdBy);

    /**
     * Appends the next version.
     *
     * <p>The version number is derived from the definition rather than supplied, so a client cannot
     * overwrite an existing version by naming its number.
     *
     * @param specificationJson the specification as submitted, in JSON. Stored verbatim so a run
     *                          can be reproduced from exactly what was authored
     */
    StoredVersion appendVersion(TestDefinitionId definitionId, String specificationJson,
            SpecificationDigest digest, boolean valid, String createdBy);
}
