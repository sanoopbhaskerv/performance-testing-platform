package com.perfplatform.persistence.run;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Spring Data access to runner shard rows. */
public interface RunnerShardJpaRepository extends JpaRepository<RunnerShardEntity, UUID> {

    /** Ordered by index so a run's shards appear in a stable sequence in reports and logs. */
    List<RunnerShardEntity> findByRunIdOrderByShardIndexAsc(UUID runId);
}
