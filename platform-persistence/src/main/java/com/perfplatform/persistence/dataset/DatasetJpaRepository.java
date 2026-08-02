package com.perfplatform.persistence.dataset;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Spring Data access to dataset version rows. */
public interface DatasetJpaRepository extends JpaRepository<DatasetEntity, UUID> {

    Optional<DatasetEntity> findByTeamIdAndNameAndVersion(UUID teamId, String name, int version);

    /** Newest first, so a listing reads the way somebody picking a version thinks. */
    List<DatasetEntity> findByTeamIdOrderByNameAscVersionDesc(UUID teamId);

    /** The highest version a team holds for a name; empty when it holds none. */
    Optional<DatasetEntity> findFirstByTeamIdAndNameOrderByVersionDesc(UUID teamId, String name);
}
