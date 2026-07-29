package com.perfplatform.persistence.artifact;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Spring Data access to artifact reference rows. */
public interface ArtifactJpaRepository extends JpaRepository<ArtifactEntity, UUID> {

    /** Oldest first, so a run's evidence reads in the order it was produced. */
    List<ArtifactEntity> findByRunIdOrderByStoredAtAsc(UUID runId);

    /** The key is the identity; used to keep a repeated pass from recording a duplicate. */
    boolean existsByObjectKey(String objectKey);
}
