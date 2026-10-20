package com.perfplatform.persistence.definition;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** Spring Data access to {@code teams}. Not exposed outside this module. */
public interface TeamJpaRepository extends JpaRepository<TeamEntity, UUID> {

    Optional<TeamEntity> findByName(String name);
}
