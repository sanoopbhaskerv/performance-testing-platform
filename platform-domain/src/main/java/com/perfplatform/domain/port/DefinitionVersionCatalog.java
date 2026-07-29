package com.perfplatform.domain.port;

import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.identity.TestDefinitionId;
import com.perfplatform.domain.identity.TestDefinitionVersionId;
import com.perfplatform.domain.spec.PerformanceTestSpecification;
import com.perfplatform.domain.spec.SpecificationDigest;

import java.util.Objects;
import java.util.Optional;

/**
 * Reads immutable definition versions, already parsed.
 *
 * <p>Orchestration needs a version's owning team and its specification, but must not know that
 * versions are rows or that specifications are stored as JSON. The composition root satisfies this
 * port by combining the storage adapter with the parser, which keeps orchestration free of both.
 *
 * <p>Read-only by design. Runs are triggered against versions; nothing in the run lifecycle may
 * create or alter one.
 */
public interface DefinitionVersionCatalog {

    /**
     * An immutable version, resolved and parsed.
     *
     * @param id            the version
     * @param definitionId  the definition it belongs to
     * @param teamId        the owning team, which is where a run's team attribution comes from — a
     *                      trigger never states its own team, so it cannot claim another's
     * @param versionNumber the contiguous version number within the definition
     * @param digest        digest of the stored specification
     * @param specification the parsed specification
     * @param executable    whether this version passed validation and any required approval; a
     *                      version that did not must never reach a runner
     */
    record DefinitionVersion(
            TestDefinitionVersionId id,
            TestDefinitionId definitionId,
            TeamId teamId,
            int versionNumber,
            SpecificationDigest digest,
            PerformanceTestSpecification specification,
            boolean executable) {

        public DefinitionVersion {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(definitionId, "definitionId");
            Objects.requireNonNull(teamId, "teamId");
            Objects.requireNonNull(digest, "digest");
            Objects.requireNonNull(specification, "specification");
            if (versionNumber < 1) {
                throw new IllegalArgumentException("versionNumber must be positive");
            }
        }
    }

    Optional<DefinitionVersion> find(TestDefinitionVersionId id);
}
