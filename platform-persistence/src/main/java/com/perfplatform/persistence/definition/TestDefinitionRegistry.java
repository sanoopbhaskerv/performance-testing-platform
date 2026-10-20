package com.perfplatform.persistence.definition;

import com.perfplatform.domain.identity.ApplicationId;
import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.identity.TestDefinitionId;
import com.perfplatform.domain.identity.TestDefinitionVersionId;
import com.perfplatform.domain.spec.SpecificationDigest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The specification registry: creates definitions and appends immutable versions to them.
 *
 * <p>This is the storage adapter behind the definition/version model. It is the only way the rest
 * of the platform writes to those tables, which is what makes the immutability rule enforceable
 * rather than merely documented.
 *
 * <p>Version creation is transactional and contiguous. Two concurrent submissions to the same
 * definition cannot both claim version <i>n</i>: the unique constraint on
 * {@code (definition_id, version_number)} rejects the loser, which surfaces as a
 * {@link ConcurrentVersionException} rather than a silently overwritten version.
 *
 * <p>Accepts typed identifiers from {@code platform-domain} rather than bare UUIDs, so a team
 * identifier cannot be passed where a definition identifier is expected — a mistake that matters
 * most in exactly this layer, where the identifier decides which tenant's data is touched.
 */
@Repository
public class TestDefinitionRegistry {

    /** Raised when a version number was taken by a concurrent writer. */
    public static class ConcurrentVersionException extends RuntimeException {
        public ConcurrentVersionException(String message) {
            super(message);
        }
    }

    /** Raised on any attempt to change what a stored version says. */
    public static class ImmutableVersionException extends RuntimeException {
        public ImmutableVersionException(String message) {
            super(message);
        }
    }

    private final TestDefinitionJpaRepository definitions;
    private final TestDefinitionVersionJpaRepository versions;
    private final Clock clock;

    public TestDefinitionRegistry(TestDefinitionJpaRepository definitions,
            TestDefinitionVersionJpaRepository versions,
            Clock clock) {
        this.definitions = definitions;
        this.versions = versions;
        this.clock = clock;
    }

    @Transactional
    public TestDefinitionEntity createDefinition(TestDefinitionId id, TeamId teamId,
            ApplicationId applicationId, String name, String createdBy) {
        Instant now = clock.instant();
        return definitions.save(new TestDefinitionEntity(
                id.value(), teamId.value(), applicationId.value(), name, createdBy, now));
    }

    /**
     * Appends a new immutable version.
     *
     * <p>The version number is derived from the definition's current pointer rather than supplied
     * by the caller, so a client cannot overwrite an existing version by naming its number.
     *
     * @throws ConcurrentVersionException if another writer took the same version number
     */
    @Transactional
    public TestDefinitionVersionEntity appendVersion(TestDefinitionId definitionId,
            TestDefinitionVersionId versionId,
            String specificationJson,
            SpecificationDigest digest,
            TestDefinitionVersionEntity.ValidationStatus validationStatus,
            String createdBy) {

        TestDefinitionEntity definition = definitions.findById(definitionId.value())
                .orElseThrow(() -> new IllegalArgumentException(
                        "No such test definition: " + definitionId));

        int versionNumber = definition.nextVersionNumber();
        if (versions.existsByDefinitionIdAndVersionNumber(definitionId.value(), versionNumber)) {
            throw new ConcurrentVersionException(
                    "Version " + versionNumber + " of definition " + definitionId
                            + " already exists. Reload the definition and retry.");
        }

        Instant now = clock.instant();
        TestDefinitionVersionEntity version = new TestDefinitionVersionEntity(
                versionId.value(), definitionId.value(), versionNumber,
                specificationJson, digest.value(), validationStatus, createdBy, now);

        TestDefinitionVersionEntity saved = versions.saveAndFlush(version);
        definition.recordNewVersion(versionNumber, now);
        definitions.save(definition);
        return saved;
    }

    /**
     * Rejects any attempt to replace a stored version.
     *
     * <p>Present so the refusal is explicit and testable at the application layer. It is the
     * first of three defences; JPA's non-updatable columns and the database trigger are the
     * other two, and only the trigger survives a direct SQL write.
     */
    @Transactional
    public void replaceVersion(TestDefinitionVersionEntity version) {
        throw new ImmutableVersionException(
                "Test definition versions are immutable. Version " + version.getVersionNumber()
                        + " of definition " + version.getDefinitionId()
                        + " cannot be replaced; append a new version instead.");
    }

    @Transactional(readOnly = true)
    public Optional<TestDefinitionEntity> findDefinition(TestDefinitionId id) {
        return definitions.findById(id.value());
    }

    @Transactional(readOnly = true)
    public Optional<TestDefinitionEntity> findDefinitionByName(TeamId teamId, String name) {
        return definitions.findByTeamIdAndName(teamId.value(), name);
    }

    @Transactional(readOnly = true)
    public List<TestDefinitionEntity> listDefinitions(TeamId teamId) {
        return definitions.findByTeamIdOrderByNameAsc(teamId.value());
    }

    @Transactional(readOnly = true)
    public Optional<TestDefinitionVersionEntity> findVersion(TestDefinitionId definitionId,
            int versionNumber) {
        return versions.findByDefinitionIdAndVersionNumber(definitionId.value(), versionNumber);
    }

    /**
     * Looks up a version by its own identifier.
     *
     * <p>The lookup a run needs: a trigger names an immutable version directly, without knowing or
     * caring which definition it belongs to.
     */
    @Transactional(readOnly = true)
    public Optional<TestDefinitionVersionEntity> findVersionById(TestDefinitionVersionId id) {
        return versions.findById(id.value());
    }

    @Transactional(readOnly = true)
    public Optional<TestDefinitionVersionEntity> findLatestVersion(TestDefinitionId definitionId) {
        return versions.findByDefinitionIdOrderByVersionNumberDesc(definitionId.value())
                .stream().findFirst();
    }

    @Transactional(readOnly = true)
    public List<TestDefinitionVersionEntity> listVersions(TestDefinitionId definitionId) {
        return versions.findByDefinitionIdOrderByVersionNumberDesc(definitionId.value());
    }

    /** Whether this exact specification has already been versioned anywhere. */
    @Transactional(readOnly = true)
    public List<TestDefinitionVersionEntity> findByDigest(SpecificationDigest digest) {
        return versions.findBySpecificationDigest(digest.value());
    }
}
