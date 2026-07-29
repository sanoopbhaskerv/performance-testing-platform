package com.perfplatform.persistence.artifact;

import com.perfplatform.domain.identity.ArtifactId;
import com.perfplatform.domain.identity.RunnerShardId;
import com.perfplatform.domain.identity.TestRunId;
import com.perfplatform.domain.port.ArtifactStore.ArtifactReference;
import com.perfplatform.domain.port.ResultCatalog;
import com.perfplatform.domain.results.ObjectiveOutcome;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable metadata for a run's artifacts and objective verdicts.
 *
 * <p>Both writes are idempotent, because reconciliation repeats. Re-storing an artifact under the
 * same key updates nothing; re-analyzing a run replaces its verdicts rather than appending a second
 * set — a report showing one objective twice, once passing and once not, is worse than no report.
 */
@Repository
public class ResultCatalogStore implements ResultCatalog {

    private final ArtifactJpaRepository artifacts;
    private final ObjectiveResultJpaRepository objectives;
    private final Clock clock;

    public ResultCatalogStore(ArtifactJpaRepository artifacts,
            ObjectiveResultJpaRepository objectives, Clock clock) {
        this.artifacts = artifacts;
        this.objectives = objectives;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void recordArtifact(ArtifactReference reference) {
        // The key is the identity. A repeated pass re-stores identical content under the same key,
        // and a second row would double-count the run's evidence.
        if (artifacts.existsByObjectKey(reference.objectKey())) {
            return;
        }

        artifacts.saveAndFlush(new ArtifactEntity(
                reference.id().value(),
                reference.runId().value(),
                reference.shardId().map(RunnerShardId::value).orElse(null),
                reference.type(),
                reference.objectKey(),
                reference.digest(),
                reference.contentType(),
                reference.sizeBytes(),
                reference.storedAt()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ArtifactReference> artifactsFor(TestRunId runId) {
        return artifacts.findByRunIdOrderByStoredAtAsc(runId.value()).stream()
                .map(ResultCatalogStore::toReference)
                .toList();
    }

    @Override
    @Transactional
    public void recordObjectiveOutcomes(TestRunId runId, List<ObjectiveOutcome> outcomes) {
        objectives.deleteByRunId(runId.value());

        for (ObjectiveOutcome outcome : outcomes) {
            objectives.save(new ObjectiveResultEntity(
                    UUID.randomUUID(),
                    runId.value(),
                    outcome.objectiveId(),
                    outcome.metric(),
                    outcome.scope(),
                    outcome.verdict(),
                    outcome.observed(),
                    outcome.threshold(),
                    outcome.detail(),
                    clock.instant()));
        }
        objectives.flush();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ObjectiveOutcome> objectiveOutcomesFor(TestRunId runId) {
        return objectives.findByRunIdOrderByObjectiveIdAsc(runId.value()).stream()
                .map(entity -> new ObjectiveOutcome(
                        entity.getObjectiveId(),
                        entity.getMetric(),
                        entity.getScope(),
                        entity.getVerdict(),
                        entity.getObservedValue(),
                        entity.getThreshold(),
                        entity.getDetail()))
                .toList();
    }

    private static ArtifactReference toReference(ArtifactEntity entity) {
        return new ArtifactReference(
                ArtifactId.of(entity.getId()),
                TestRunId.of(entity.getRunId()),
                Optional.ofNullable(entity.getShardId()).map(RunnerShardId::of),
                entity.getArtifactType(),
                entity.getObjectKey(),
                entity.getDigest(),
                entity.getContentType(),
                entity.getSizeBytes(),
                entity.getStoredAt());
    }
}
