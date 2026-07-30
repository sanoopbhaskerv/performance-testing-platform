package com.perfplatform.orchestration.run;

import com.perfplatform.domain.identity.TestRunId;
import com.perfplatform.domain.plan.ExecutionPlan;
import com.perfplatform.domain.port.ArtifactStore;
import com.perfplatform.domain.port.ArtifactStore.ArtifactDescriptor;
import com.perfplatform.domain.port.ArtifactStore.ArtifactReference;
import com.perfplatform.domain.port.ArtifactStore.ArtifactType;
import com.perfplatform.domain.port.DefinitionVersionCatalog.DefinitionVersion;
import com.perfplatform.domain.port.ResultCatalog;
import com.perfplatform.domain.port.RunAnalyzer;
import com.perfplatform.domain.port.TargetResolver;
import com.perfplatform.domain.results.SampleSet;
import com.perfplatform.domain.run.RunnerShard;
import com.perfplatform.domain.run.TestRun;
import com.perfplatform.domain.spec.PerformanceTestSpecification;
import com.perfplatform.domain.target.TargetAddress;
import com.perfplatform.engine.spi.ExecutionEngineProvider;
import com.perfplatform.engine.spi.RenderedWorkload;
import com.perfplatform.engine.spi.RunnerOutput;
import com.perfplatform.engine.spi.ShardAssignment;
import com.perfplatform.orchestration.plan.EngineRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The engine-facing half of a run: preparing what executes, and reducing what came back.
 *
 * <p>Grouped into one collaborator so the reconciler gains a single dependency rather than five. It
 * is also a genuine unit of responsibility — both operations are "talk to the engine, go through the
 * artifact store" — and the reconciler stays about lifecycle: it decides <em>when</em> a workload is
 * prepared and <em>when</em> results are analyzed, and knows nothing about JMX or JTL.
 *
 * <p>The artifact store is the handoff between the control plane and a runner, in both directions.
 * The rendered workload goes in before the runner starts; the results come out after it finishes.
 * That is what makes the same code path work for a local process and for a Kubernetes Job that
 * uploads its own output (CLAUDE.md §15, §17) — and it is why nothing here ever holds a file path.
 */
public final class RunWorkloadService {

    private static final Logger log = LoggerFactory.getLogger(RunWorkloadService.class);

    private final EngineRegistry engines;
    private final ArtifactStore artifacts;
    private final ResultCatalog results;
    private final RunAnalyzer analyzer;
    private final TargetResolver targets;

    public RunWorkloadService(EngineRegistry engines, ArtifactStore artifacts, ResultCatalog results,
            RunAnalyzer analyzer, TargetResolver targets) {
        this.engines = Objects.requireNonNull(engines, "engines");
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.results = Objects.requireNonNull(results, "results");
        this.analyzer = Objects.requireNonNull(analyzer, "analyzer");
        this.targets = Objects.requireNonNull(targets, "targets");
    }

    /** Raised when a run cannot be pointed at a permitted target. */
    public static class TargetUnresolvableException extends RuntimeException {
        public TargetUnresolvableException(String applicationName, String environment) {
            super("No permitted target is registered for application '" + applicationName
                    + "' in environment '" + environment + "'. A run cannot be started without one.");
        }
    }

    /**
     * Renders one shard's workload and stores it.
     *
     * <p>Stored before the runner is asked to start, so the file that executed is recoverable
     * afterwards whatever happens to the runner. It is the evidence of what actually ran, which a
     * result nobody can reproduce is not.
     *
     * @return the stored reference, so the caller can pass its key to the runner
     */
    public ArtifactReference prepareShard(TestRun run, ExecutionPlan plan, DefinitionVersion version,
            RunnerShard shard) {

        PerformanceTestSpecification specification = version.specification();
        ExecutionEngineProvider engine = engines.require(plan.engine());

        TargetAddress target = resolveTarget(run, specification);

        ShardAssignment assignment = new ShardAssignment(
                run.id(), shard.shardIndex(), shard.shardCount(),
                correlationHeader(specification));

        RenderedWorkload rendered = engine.render(specification, plan, assignment, target);

        ArtifactReference stored = artifacts.store(
                new ArtifactDescriptor(run.teamId(), run.id(), Optional.of(shard.id()),
                        ArtifactType.EXECUTION_PLAN, rendered.fileName(), rendered.contentType()),
                rendered.bytes());
        results.recordArtifact(stored);

        log.info("Workload prepared runId={} shardIndex={} engine={} target={} artifact={} bytes={}",
                run.id(), shard.shardIndex(), plan.engine(), target.baseUrl(),
                stored.objectKey(), stored.sizeBytes());

        return stored;
    }

    /**
     * The address this run is permitted to point at.
     *
     * @throws TargetUnresolvableException if none is registered. Refused rather than guessed:
     *                                    pointing a thousand virtual users at an inferred host is
     *                                    the single worst thing this platform could do by accident
     */
    public TargetAddress resolveTarget(TestRun run, PerformanceTestSpecification specification) {
        String applicationName = applicationName(specification);

        return targets.resolve(run.teamId(), applicationName, run.environment())
                .orElseThrow(() ->
                        new TargetUnresolvableException(applicationName, run.environment()));
    }

    /**
     * Reduces a run's stored results and judges them against its objectives.
     *
     * <p>Reads the results from the artifact store rather than from the provider, so analysis works
     * identically for a local process and for a runner that uploaded its own output — and so it can
     * be re-run later from exactly the same inputs.
     *
     * <p>A shard whose results are missing is <em>not</em> skipped: it lowers the reported shard
     * count, which is what makes every objective indeterminate rather than passing on partial data.
     */
    public RunAnalyzer.Analysis analyze(TestRun run, ExecutionPlan plan, DefinitionVersion version,
            List<RunnerShard> shards) {

        ExecutionEngineProvider engine = engines.require(plan.engine());

        List<RunnerOutput.ShardResult> shardResults = new ArrayList<>();
        for (RunnerShard shard : shards) {
            resultDataFor(run.id(), shard).ifPresent(data ->
                    shardResults.add(new RunnerOutput.ShardResult(shard.shardIndex(), data)));
        }

        int expected = Math.max(1, plan.capacity().shardCount());
        SampleSet samples = engine.parse(
                new RunnerOutput(run.id(), expected, shardResults));

        RunAnalyzer.Analysis analysis = analyzer.analyze(run.id(), plan.engine(), expected,
                shardResults.size(), samples, objectives(version.specification()));

        results.recordObjectiveOutcomes(run.id(), analysis.outcomes());

        log.info("Run analyzed runId={} shardsReported={}/{} samples={} unreadable={} objectives={} "
                        + "successful={}",
                run.id(), shardResults.size(), expected, samples.samples().size(),
                samples.unparsedRows(), analysis.outcomes().size(), analysis.isSuccessful());

        return analysis;
    }

    /**
     * The labels this run's results will be reported under, as {@code workload/scenario}.
     *
     * <p>Derived from the specification so the platform and the runner agree on them. Objectives are
     * scoped by workload and by scenario, so results labelled any other way could not be judged.
     */
    public static List<String> operationLabels(PerformanceTestSpecification specification) {
        if (specification.spec() == null) {
            return List.of();
        }
        List<String> labels = new ArrayList<>();
        specification.spec().workloads().forEach(workload ->
                workload.scenarios().forEach(scenario ->
                        labels.add(workload.name() + "/" + scenario.name())));
        return List.copyOf(labels);
    }

    /** This shard's results content, if it produced any. */
    private Optional<String> resultDataFor(TestRunId runId, RunnerShard shard) {
        return results.artifactsFor(runId).stream()
                .filter(reference -> reference.type() == ArtifactType.RESULTS)
                .filter(reference -> reference.shardId()
                        .map(id -> id.equals(shard.id()))
                        .orElse(false))
                .findFirst()
                .flatMap(artifacts::read)
                .map(bytes -> new String(bytes, StandardCharsets.UTF_8));
    }

    private static List<PerformanceTestSpecification.ObjectiveSpec> objectives(
            PerformanceTestSpecification specification) {
        return specification.spec() == null ? List.of() : specification.spec().objectives();
    }

    private static String applicationName(PerformanceTestSpecification specification) {
        return specification.metadata() == null ? "" : specification.metadata().application();
    }

    private static String correlationHeader(PerformanceTestSpecification specification) {
        if (specification.spec() == null || specification.spec().observability() == null) {
            return ShardAssignment.DEFAULT_CORRELATION_HEADER;
        }
        return specification.spec().observability().correlationHeader();
    }
}
