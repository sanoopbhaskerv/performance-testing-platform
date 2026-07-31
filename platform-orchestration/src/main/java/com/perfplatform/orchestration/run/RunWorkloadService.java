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
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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

    /**
     * What every objective on a synthetic run says instead of a comparison.
     *
     * <p>Shared with the reconciler's failure reason so the run's status and its objectives give the
     * same explanation. Two wordings for one fact is how one of them ends up being the reassuring one.
     */
    public static final String SYNTHETIC_OBJECTIVE_DETAIL =
            "No load was generated, so nothing could be measured.";

    /**
     * Finds {@code "planDigest": "sha256:…"} in a runner manifest.
     *
     * <p>Tolerant of whitespace, because the manifest is written by a runner image that may not be
     * this repository's, and strict about the value, because the value is what gates acceptance.
     */
    private static final Pattern PLAN_DIGEST =
            Pattern.compile("\"planDigest\"\\s*:\\s*\"([^\"]+)\"");

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
     *
     * <p>A run marked {@linkplain TestRun#synthetic() synthetic} has its verdicts restated as
     * indeterminate before they are stored. The measurements are still recorded — they are what the
     * artifacts contain — but no objective on a run that generated no load may report a pass.
     */
    public RunAnalyzer.Analysis analyze(TestRun run, ExecutionPlan plan, DefinitionVersion version,
            List<RunnerShard> shards) {

        ExecutionEngineProvider engine = engines.require(plan.engine());

        // Adopt anything a remote runner uploaded before looking for results. A Kubernetes runner
        // writes to object storage and cannot write to the database — it has no credentials for one
        // and must never have any (a provider that could write run state could mark its own failures
        // as successes). Without this step a Job's output would be invisible to analysis.
        adoptUploadedArtifacts(run, plan);

        List<RunnerOutput.ShardResult> shardResults = new ArrayList<>();
        for (RunnerShard shard : shards) {
            resultDataFor(run.id(), shard).ifPresent(data ->
                    shardResults.add(new RunnerOutput.ShardResult(shard.shardIndex(), data)));
        }

        int expected = Math.max(1, plan.capacity().shardCount());
        SampleSet samples = engine.parse(
                new RunnerOutput(run.id(), expected, shardResults));

        RunAnalyzer.Analysis measured = analyzer.analyze(run.id(), plan.engine(), expected,
                shardResults.size(), samples, objectives(version.specification()));

        // Applied here rather than inside the analyzer so the analyzer stays a pure function of the
        // samples: what it computes must be re-derivable from stored artifacts alone, and whether a
        // provider generated load is a fact about the run, not about the samples.
        RunAnalyzer.Analysis analysis = run.synthetic()
                ? measured.asIndeterminate(SYNTHETIC_OBJECTIVE_DETAIL)
                : measured;

        // Metrics and verdicts in one call, therefore one transaction. Stored measurements without
        // the verdicts derived from them, or the reverse, would be a report assembled from two
        // different analyses.
        results.recordAnalysis(run.id(), analysis.result(), analysis.outcomes());

        log.info("Run analyzed runId={} shardsReported={}/{} samples={} unreadable={} objectives={} "
                        + "successful={} synthetic={}",
                run.id(), shardResults.size(), expected, samples.samples().size(),
                samples.unparsedRows(), analysis.outcomes().size(), analysis.isSuccessful(),
                run.synthetic());

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

    /**
     * Raised when a runner's output describes a different plan than the run references.
     *
     * <p>Its own type because merging such results would be worse than losing them: the numbers
     * would be real, the report would look ordinary, and it would describe a test nobody approved.
     */
    public static class PlanDigestMismatchException extends RuntimeException {
        public PlanDigestMismatchException(TestRunId runId, String expected, String reported) {
            super("Run " + runId + " references plan digest " + expected
                    + " but a runner reported results for " + reported
                    + ". The results describe a different test and will not be accepted.");
        }
    }

    /**
     * Records artifacts a runner uploaded directly, and refuses any that describe another plan.
     *
     * <p>Two halves, and both matter.
     *
     * <p><b>Adoption.</b> The local providers store artifacts and record them in one step because
     * they share a process with the control plane. A runner Pod does not: it uploads to object
     * storage and has no database access at all, by design. Reconciliation therefore has to notice
     * what appeared. Doing it here — immediately before results are read — means it happens exactly
     * once per analysis and cannot be forgotten by a caller.
     *
     * <p><b>Verification.</b> Every runner echoes back the plan digest it was given, and it is
     * compared against the run's own plan before anything is accepted (CLAUDE.md §15). A stale Job,
     * a Job name reused across runs, or a runner handed the wrong key would otherwise contribute
     * real-looking measurements of the wrong test.
     *
     * <p>Idempotent: {@code recordArtifact} deduplicates by object key, so a repeated pass over the
     * same uploads is a no-op rather than a second set of evidence.
     */
    private void adoptUploadedArtifacts(TestRun run, ExecutionPlan plan) {
        Set<String> known = results.artifactsFor(run.id()).stream()
                .map(ArtifactReference::objectKey)
                .collect(Collectors.toSet());

        List<ArtifactReference> uploaded;
        try {
            uploaded = artifacts.listForRun(run.id());
        } catch (RuntimeException e) {
            // Storage being briefly unreadable must not fail the run. Analysis proceeds with what is
            // already recorded, and reports fewer shards than expected — which makes every objective
            // indeterminate rather than passing on partial data.
            log.warn("Could not list uploaded artifacts for runId={}; analysing what is already "
                    + "recorded", run.id(), e);
            return;
        }

        int adopted = 0;
        for (ArtifactReference reference : uploaded) {
            if (known.contains(reference.objectKey())) {
                continue;
            }
            results.recordArtifact(reference);
            adopted++;
        }

        if (adopted > 0) {
            log.info("Adopted {} runner-uploaded artifacts runId={} teamId={}",
                    adopted, run.id(), run.teamId());
        }

        verifyPlanIdentity(run, plan);
    }

    /**
     * Checks every runner manifest against the plan the run actually references.
     *
     * <p>A manifest that carries no digest is not treated as a mismatch — an older runner, or one
     * whose upload was cut short, has said nothing rather than said something wrong. A manifest that
     * carries a <em>different</em> digest is refused outright.
     */
    private void verifyPlanIdentity(TestRun run, ExecutionPlan plan) {
        String expected = plan.planDigest().value();

        for (ArtifactReference reference : results.artifactsFor(run.id())) {
            if (reference.type() != ArtifactType.RUNNER_MANIFEST) {
                continue;
            }
            Optional<String> reported = artifacts.read(reference)
                    .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                    .flatMap(RunWorkloadService::planDigestIn);

            if (reported.isPresent() && !expected.equals(reported.get())) {
                log.error("Plan digest mismatch runId={} expected={} reported={} artifact={}",
                        run.id(), expected, reported.get(), reference.objectKey());
                throw new PlanDigestMismatchException(run.id(), expected, reported.get());
            }
        }
    }

    /**
     * Reads {@code planDigest} out of a runner manifest.
     *
     * <p>Hand-parsed for the same reason the domain has no Jackson: this reads a file a runner wrote,
     * and the only field that matters is one flat string. A full parse would fail on a manifest with
     * an unexpected shape, turning "the digest could not be read" — which is tolerated — into a
     * crash during analysis.
     */
    static Optional<String> planDigestIn(String manifestJson) {
        Matcher matcher = PLAN_DIGEST.matcher(manifestJson);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
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
