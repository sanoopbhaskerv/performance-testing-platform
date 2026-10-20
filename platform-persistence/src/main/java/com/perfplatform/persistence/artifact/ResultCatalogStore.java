package com.perfplatform.persistence.artifact;

import com.perfplatform.domain.identity.ArtifactId;
import com.perfplatform.domain.identity.RunnerShardId;
import com.perfplatform.domain.identity.TestRunId;
import com.perfplatform.domain.port.ArtifactStore.ArtifactReference;
import com.perfplatform.domain.port.ResultCatalog;
import com.perfplatform.domain.results.NormalizedRunResult;
import com.perfplatform.domain.results.ObjectiveOutcome;
import com.perfplatform.domain.results.OperationMetrics;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final RunMetricJpaRepository metrics;
    private final Clock clock;

    public ResultCatalogStore(ArtifactJpaRepository artifacts,
            ObjectiveResultJpaRepository objectives, RunMetricJpaRepository metrics, Clock clock) {
        this.artifacts = artifacts;
        this.objectives = objectives;
        this.metrics = metrics;
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

    /**
     * {@inheritDoc}
     *
     * <p>One transaction covering both writes, and both are replacements. A crash between them would
     * otherwise leave a run whose stored p95 came from one analysis and whose verdict came from
     * another — a comparison nobody ever made, presented as the run's conclusion.
     */
    @Override
    @Transactional
    public void recordAnalysis(TestRunId runId, NormalizedRunResult result,
            List<ObjectiveOutcome> outcomes) {

        Instant recordedAt = clock.instant();

        metrics.deleteByRunId(runId.value());
        objectives.deleteByRunId(runId.value());
        // Forced before the inserts: both deletes and inserts target rows the unique constraints
        // cover, and Hibernate would otherwise be free to order the inserts first and violate them.
        metrics.flush();
        objectives.flush();

        metrics.save(toEntity(runId, result, result.overall(), recordedAt));
        result.byOperation().values().forEach(operation ->
                metrics.save(toEntity(runId, result, operation, recordedAt)));

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
                    recordedAt));
        }

        metrics.flush();
        objectives.flush();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Reconstructed from the rows rather than from a stored blob, so the columns a report reads are
     * the columns an operator can query. The aggregate row is required: without it there is nothing to
     * judge a whole-test objective against, and inventing one by summing the operations would produce
     * a p95 that is not any operation's and not the run's.
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<NormalizedRunResult> runMetricsFor(TestRunId runId) {
        List<RunMetricEntity> rows = metrics.findByRunIdOrderByOperationAsc(runId.value());
        if (rows.isEmpty()) {
            return Optional.empty();
        }

        RunMetricEntity aggregate = rows.stream()
                .filter(row -> OperationMetrics.ALL.equals(row.getOperation()))
                .findFirst()
                .orElse(null);
        if (aggregate == null) {
            // Unreachable through recordAnalysis, which always writes it. Refused rather than
            // patched over: a report is a claim about what a run measured, and assembling one from
            // rows that do not include the run-wide summary would be a claim about something else.
            throw new IllegalStateException("Run " + runId + " has metric rows but no '"
                    + OperationMetrics.ALL + "' aggregate row. Its analysis is incomplete and a "
                    + "report cannot be assembled from it.");
        }

        Map<String, OperationMetrics> byOperation = new LinkedHashMap<>();
        rows.stream()
                .filter(row -> !OperationMetrics.ALL.equals(row.getOperation()))
                .forEach(row -> byOperation.put(row.getOperation(), toMetrics(row)));

        return Optional.of(new NormalizedRunResult(
                runId,
                aggregate.getEngine(),
                Optional.ofNullable(aggregate.getMeasuredFrom()),
                Optional.ofNullable(aggregate.getMeasuredTo()),
                aggregate.getShardsExpected(),
                aggregate.getShardsReported(),
                toMetrics(aggregate),
                byOperation));
    }

    private static RunMetricEntity toEntity(TestRunId runId, NormalizedRunResult result,
            OperationMetrics operation, Instant recordedAt) {

        RunMetricEntity entity = new RunMetricEntity(
                UUID.randomUUID(),
                runId.value(),
                operation.name(),
                result.engine(),
                operation.sampleCount(),
                operation.errorCount(),
                operation.unparsedCount(),
                operation.minMillis(),
                operation.maxMillis(),
                operation.meanMillis(),
                operation.p50Millis(),
                operation.p90Millis(),
                operation.p95Millis(),
                operation.p99Millis(),
                operation.throughputPerSecondMilli(),
                result.shardsExpected(),
                result.shardsReported(),
                result.startedAt().orElse(null),
                result.completedAt().orElse(null),
                recordedAt);
        entity.setP75Millis(operation.p75Millis());
        return entity;
    }

    private static OperationMetrics toMetrics(RunMetricEntity row) {
        return new OperationMetrics(
                row.getOperation(),
                row.getSampleCount(),
                row.getErrorCount(),
                row.getUnparsedCount(),
                row.getMinMillis(),
                row.getMaxMillis(),
                row.getMeanMillis(),
                row.getP50Millis(),
                row.getP90Millis(),
                row.getP95Millis(),
                row.getP99Millis(),
                row.getThroughputPerSecondMilli(), row.getP75Millis());
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
