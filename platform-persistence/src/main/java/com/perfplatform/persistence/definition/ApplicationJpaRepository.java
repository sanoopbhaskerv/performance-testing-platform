package com.perfplatform.persistence.definition;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Spring Data access to {@code applications}. Not exposed outside this module. */
public interface ApplicationJpaRepository extends JpaRepository<ApplicationEntity, UUID> {

    /** Application names are unique within a team, not globally. */
    Optional<ApplicationEntity> findByTeamIdAndName(UUID teamId, String name);

    List<ApplicationEntity> findByTeamId(UUID teamId);
}
