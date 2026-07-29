package com.perfplatform.persistence.definition;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Spring Data access to {@code test_definition_versions}. Not exposed outside this module. */
public interface TestDefinitionVersionJpaRepository
        extends JpaRepository<TestDefinitionVersionEntity, UUID> {

    Optional<TestDefinitionVersionEntity> findByDefinitionIdAndVersionNumber(
            UUID definitionId, int versionNumber);

    List<TestDefinitionVersionEntity> findByDefinitionIdOrderByVersionNumberDesc(UUID definitionId);

    /**
     * Content-addressed lookup: answers whether this exact specification has already been
     * versioned, so an unchanged resubmission does not create a redundant version.
     */
    List<TestDefinitionVersionEntity> findBySpecificationDigest(String specificationDigest);

    boolean existsByDefinitionIdAndVersionNumber(UUID definitionId, int versionNumber);
}
