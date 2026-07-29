package com.perfplatform.persistence.definition;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Spring Data access to {@code test_definitions}. Not exposed outside this module. */
public interface TestDefinitionJpaRepository extends JpaRepository<TestDefinitionEntity, UUID> {

    Optional<TestDefinitionEntity> findByTeamIdAndName(UUID teamId, String name);

    List<TestDefinitionEntity> findByTeamIdOrderByNameAsc(UUID teamId);
}
