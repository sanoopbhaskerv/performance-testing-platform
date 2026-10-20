package com.perfplatform.persistence.definition;

import com.perfplatform.domain.identity.ApplicationId;
import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.identity.TestDefinitionId;
import com.perfplatform.domain.identity.TestDefinitionVersionId;
import com.perfplatform.domain.port.TestDefinitionRepository;
import com.perfplatform.domain.spec.SpecificationDigest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * The write side of the specification registry, as a domain port.
 *
 * <p>A thin adapter over {@link TestDefinitionRegistry}, which already owns the immutability and
 * contiguous-numbering rules. Its job is to keep JPA entities out of everything above persistence:
 * the registry returns entities, and an API controller that received one would make the storage
 * schema the public contract.
 */
@Repository
public class TestDefinitionStore implements TestDefinitionRepository {

    private final TestDefinitionRegistry registry;

    public TestDefinitionStore(TestDefinitionRegistry registry) {
        this.registry = registry;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TestDefinitionSummary> find(TestDefinitionId id) {
        return registry.findDefinition(id).map(TestDefinitionStore::toSummary);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TestDefinitionSummary> findByName(TeamId teamId, String name) {
        return registry.findDefinitionByName(teamId, name).map(TestDefinitionStore::toSummary);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TestDefinitionSummary> listForTeam(TeamId teamId) {
        return registry.listDefinitions(teamId).stream().map(TestDefinitionStore::toSummary).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<StoredVersion> listVersions(TestDefinitionId definitionId) {
        return registry.listVersions(definitionId).stream().map(TestDefinitionStore::toVersion).toList();
    }

    @Override
    @Transactional
    public TestDefinitionSummary create(TeamId teamId, ApplicationId applicationId, String name,
            String createdBy) {
        if (registry.findDefinitionByName(teamId, name).isPresent()) {
            throw new DuplicateDefinitionException(name);
        }
        try {
            return toSummary(registry.createDefinition(
                    TestDefinitionId.generate(), teamId, applicationId, name, createdBy));
        } catch (DataIntegrityViolationException e) {
            // The unique constraint on (team_id, name) is authoritative: a preceding read would
            // pass on two replicas at once.
            throw new DuplicateDefinitionException(name);
        }
    }

    @Override
    @Transactional
    public StoredVersion appendVersion(TestDefinitionId definitionId, String specificationJson,
            SpecificationDigest digest, boolean valid, String createdBy) {

        return toVersion(registry.appendVersion(
                definitionId,
                TestDefinitionVersionId.generate(),
                specificationJson,
                digest,
                valid ? TestDefinitionVersionEntity.ValidationStatus.VALID
                        : TestDefinitionVersionEntity.ValidationStatus.INVALID,
                createdBy));
    }

    private static TestDefinitionSummary toSummary(TestDefinitionEntity entity) {
        return new TestDefinitionSummary(
                TestDefinitionId.of(entity.getId()),
                TeamId.of(entity.getTeamId()),
                ApplicationId.of(entity.getApplicationId()),
                entity.getName(),
                entity.getLatestVersion(),
                entity.getCreatedBy(),
                entity.getCreatedAt());
    }

    private static StoredVersion toVersion(TestDefinitionVersionEntity entity) {
        return new StoredVersion(
                TestDefinitionVersionId.of(entity.getId()),
                TestDefinitionId.of(entity.getDefinitionId()),
                entity.getVersionNumber(),
                SpecificationDigest.parse(entity.getSpecificationDigest()),
                entity.getValidationStatus() == TestDefinitionVersionEntity.ValidationStatus.VALID,
                entity.getCreatedBy(),
                entity.getCreatedAt());
    }
}
