package com.perfplatform.orchestration.run;

import com.perfplatform.domain.dataset.DatasetPartitioner;
import com.perfplatform.domain.dataset.DatasetUri;
import com.perfplatform.domain.execution.DatasetDelivery;
import com.perfplatform.domain.execution.RuntimeCredential;
import com.perfplatform.domain.execution.SecretFileDelivery;
import com.perfplatform.domain.identity.TestRunId;
import com.perfplatform.domain.plan.ExecutionPlan;
import com.perfplatform.domain.port.ArtifactStore;
import com.perfplatform.domain.port.ArtifactStore.ArtifactDescriptor;
import com.perfplatform.domain.port.ArtifactStore.ArtifactReference;
import com.perfplatform.domain.port.ArtifactStore.ArtifactType;
import com.perfplatform.domain.port.DatasetCatalog;
import com.perfplatform.domain.port.DatasetCatalog.DatasetVersion;
import com.perfplatform.domain.port.DatasetStore;
import com.perfplatform.domain.port.DefinitionVersionCatalog.DefinitionVersion;
import com.perfplatform.domain.port.ProgressCatalog;
import com.perfplatform.domain.port.ResultCatalog;
import com.perfplatform.domain.port.RunAnalyzer;
import com.perfplatform.domain.port.SessionPoolProvider;
import com.perfplatform.domain.port.TargetResolver;
import com.perfplatform.domain.progress.LiveProgress;
import com.perfplatform.domain.progress.ShardProgress;
import com.perfplatform.domain.results.SampleSet;
import com.perfplatform.domain.run.RunnerShard;
import com.perfplatform.domain.run.TestRun;
import com.perfplatform.domain.spec.PerformanceTestSpecification;
import com.perfplatform.domain.spec.PerformanceTestSpecification.DatasetSpec;
import com.perfplatform.domain.spec.WorkloadType;
import com.perfplatform.domain.target.TargetAddress;
import com.perfplatform.engine.spi.ExecutionEngineProvider;
import com.perfplatform.engine.spi.RenderedWorkload;
import com.perfplatform.engine.spi.RunnerOutput;
import com.perfplatform.engine.spi.ShardAssignment;
import com.perfplatform.orchestration.plan.EngineRegistry;
import com.perfplatform.orchestration.progress.ShardProgressCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /** The runner workspace file JMeter reads pooled session rows from. */
    public static final String SESSION_DATASET_FILE_NAME = "sessions.csv";

    private final EngineRegistry engines;
    private final ArtifactStore artifacts;
    private final ResultCatalog results;
    private final RunAnalyzer analyzer;
    private final TargetResolver targets;
    private final DatasetCatalog datasetCatalog;
    private final DatasetStore datasetStore;
    private final SessionPoolProvider sessionPools;
    private final ProgressCatalog progress;

    /** Turns a runner's published snapshot into a value. Stateless, so one instance is enough. */
    private final ShardProgressCodec progressCodec = new ShardProgressCodec();

    public RunWorkloadService(EngineRegistry engines, ArtifactStore artifacts, ResultCatalog results,
            RunAnalyzer analyzer, TargetResolver targets, DatasetCatalog datasetCatalog,
            DatasetStore datasetStore, SessionPoolProvider sessionPools, ProgressCatalog progress) {
        this.engines = Objects.requireNonNull(engines, "engines");
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.results = Objects.requireNonNull(results, "results");
        this.analyzer = Objects.requireNonNull(analyzer, "analyzer");
        this.targets = Objects.requireNonNull(targets, "targets");
        this.datasetCatalog = Objects.requireNonNull(datasetCatalog, "datasetCatalog");
        this.datasetStore = Objects.requireNonNull(datasetStore, "datasetStore");
        this.sessionPools = Objects.requireNonNull(sessionPools, "sessionPools");
        this.progress = Objects.requireNonNull(progress, "progress");
    }

    /** Raised when a run cannot be pointed at a permitted target. */
    public static class TargetUnresolvableException extends RuntimeException {
        public TargetUnresolvableException(String applicationName, String environment) {
            super("No permitted target is registered for application '" + applicationName
                    + "' in environment '" + environment + "'. A run cannot be started without one.");
        }
    }

    /**
     * Raised when a run's declared dataset cannot be delivered as written.
     *
     * <p>Fatal to the run, on purpose, and this is the decision worth defending. A missing or
     * mismatched dataset does not stop JMeter: every unresolved {@code ${orderId}} is sent as those
     * literal characters, the target answers 404 to all of it in a few milliseconds, and the run
     * reports excellent latency for a system that did no work. That is the failure mode this
     * platform is most suspicious of, so it is refused <em>before</em> a single request is made
     * rather than surfaced afterwards as a curiously fast result.
     */
    public static class DatasetUnavailableException extends RuntimeException {
        public DatasetUnavailableException(String message) {
            super(message);
        }
    }

    /** Raised when pooled application sessions cannot safely cover the run window. */
    public static class SessionUnavailableException extends RuntimeException {
        public SessionUnavailableException(String message) {
            super(message);
        }
    }

    /**
     * One shard's inputs: the workload it executes, and the data it sends.
     *
     * @param workload the rendered plan, stored before the runner starts
     * @param datasets this shard's partitions, already sliced so no two shards send the same row
     * @param secretFiles this shard's ephemeral secret files, delivered outside artifact storage
     */
    public record PreparedShard(
            ArtifactReference workload,
            List<DatasetDelivery> datasets,
            List<SecretFileDelivery> secretFiles) {

        public PreparedShard {
            datasets = List.copyOf(datasets);
            secretFiles = List.copyOf(secretFiles);
        }
    }

    /**
     * Renders one shard's workload, slices its data, and stores both.
     *
     * <p>Stored before the runner is asked to start, so what executed is recoverable afterwards
     * whatever happens to the runner — and that now covers the inputs as well as the plan. A result
     * is reproducible only if the rows that produced it are still retrievable; a partition that
     * existed only inside a pod would leave "which orders did shard 3 submit?" permanently
     * unanswerable.
     *
     * @throws DatasetUnavailableException if a declared dataset is missing, unreadable or does not
     *                                    carry the columns the specification says it has
     */
    public PreparedShard prepareShard(TestRun run, ExecutionPlan plan, DefinitionVersion version,
            RunnerShard shard, List<RuntimeCredential> runtimeCredentials, Duration deadline,
            Instant now) {

        PerformanceTestSpecification specification = version.specification();
        ExecutionEngineProvider engine = engines.require(plan.engine());
        if ("playwright".equals(plan.engine())) {
            specification.spec().sources().stream()
                    .filter(source -> source.uri() != null && source.uri().startsWith("artifact://"))
                    .forEach(source -> com.perfplatform.domain.port.BrowserSourceStore.requireOwned(
                            run.teamId(), source.uri(), source.digest()));
        }

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

        List<DatasetDelivery> datasets = new ArrayList<>(
                prepareDatasets(run, specification, engine, shard));
        List<SecretFileDelivery> secretFiles = new ArrayList<>();
        prepareSessionFile(run, specification, shard, runtimeCredentials, deadline, now)
                .ifPresent(secretFiles::add);

        log.info("Workload prepared runId={} shardIndex={} engine={} target={} artifact={} bytes={} "
                        + "datasets={}",
                run.id(), shard.shardIndex(), plan.engine(), target.baseUrl(),
                stored.objectKey(), stored.sizeBytes(), datasets.size());

        return new PreparedShard(stored, datasets, secretFiles);
    }

    /** The shape that predates runtime session delivery. */
    public PreparedShard prepareShard(TestRun run, ExecutionPlan plan, DefinitionVersion version,
            RunnerShard shard) {
        return prepareShard(run, plan, version, shard, List.of(), Duration.ofHours(1),
                Instant.EPOCH);
    }

    /**
     * Resolves, verifies and slices every dataset the specification declares.
     *
     * <p>Three checks, in this order, each refusing rather than degrading:
     *
     * <ol>
     *   <li><b>The reference resolves.</b> {@code dataset://orders/3} must name a version this
     *       team uploaded. A team's own prefix is the only place looked, so a specification cannot
     *       reach another team's data by naming it.</li>
     *   <li><b>The columns match.</b> Every column the specification declares must be present in the
     *       file's header. This is what turns {@code spec.datasets[].columns} from a second source of
     *       truth into a contract — the same shape as the plan digest. A dataset re-exported with
     *       {@code order_id} where the test expects {@code orderId} passes validation, because
     *       validation ran against the declaration, and would then send the literal text.</li>
     *   <li><b>The content is readable.</b> A catalog row whose object is gone is a dataset that
     *       resolves and delivers nothing.</li>
     * </ol>
     *
     * <p>Extra columns in the file are fine and deliberately not reported: a dataset legitimately
     * carries more than one test uses, and refusing that would make datasets un-shareable.
     */
    private List<DatasetDelivery> prepareDatasets(TestRun run,
            PerformanceTestSpecification specification, ExecutionEngineProvider engine,
            RunnerShard shard) {

        if (specification.spec() == null || specification.spec().datasets().isEmpty()) {
            return List.of();
        }

        List<DatasetDelivery> deliveries = new ArrayList<>();
        for (DatasetSpec declared : specification.spec().datasets()) {
            DatasetUri uri = DatasetUri.parse(declared.source() == null ? null
                            : declared.source().uri())
                    .orElseThrow(() -> new DatasetUnavailableException(
                            "Dataset '" + declared.name() + "' does not name a stored dataset "
                                    + "version. Its source uri must be dataset://<name>/<version>, "
                                    + "which is what an upload returns."));

            DatasetVersion stored = datasetCatalog.find(run.teamId(), uri.name(), uri.version())
                    .orElseThrow(() -> new DatasetUnavailableException(
                            "Dataset " + uri + " is not registered for this team, so the journeys "
                                    + "bound to it would send unfilled ${...} references."));

            List<String> missing = declared.columns().stream()
                    .filter(column -> !stored.columns().contains(column))
                    .toList();
            if (!missing.isEmpty()) {
                throw new DatasetUnavailableException(
                        "Dataset " + uri + " does not have the columns this test declares: "
                                + missing + ". The stored file has " + stored.columns() + ". "
                                + "A journey binding to a missing column would send the literal "
                                + "text ${name}, and every such request would fail while the run "
                                + "reported fast, healthy-looking latency.");
            }

            byte[] content = datasetStore.read(stored.objectKey())
                    .orElseThrow(() -> new DatasetUnavailableException(
                            "Dataset " + uri + " is registered but its content could not be read, "
                                    + "so this run has no data to send."));

            deliveries.add(sliceAndStore(run, shard, engine, declared, uri,
                    new String(content, StandardCharsets.UTF_8)));
        }
        return List.copyOf(deliveries);
    }

    /**
     * This shard's slice of one dataset, stored as an artifact of the run.
     *
     * <p>A dataset that is not partitioned by shard is delivered whole to every shard. That is what
     * the specification asked for and the validator already warns about it: with more than one shard
     * every generator replays the same rows, which distorts the result and can duplicate writes to
     * the target.
     */
    private DatasetDelivery sliceAndStore(TestRun run, RunnerShard shard,
            ExecutionEngineProvider engine, DatasetSpec declared, DatasetUri uri, String csv) {

        String slice = declared.partitionByShard()
                ? DatasetPartitioner.partition(csv, shard.shardCount())
                        .get(shard.shardIndex()).toCsv()
                : csv;

        // The stored object carries a prefix and the delivered file does not. A partition is a CSV
        // and so is a JMeter results file; without something in the object name to tell them apart,
        // an input would be adopted as a result and its rows parsed as measurements.
        ArtifactReference stored = artifacts.store(
                new ArtifactDescriptor(run.teamId(), run.id(), Optional.of(shard.id()),
                        ArtifactType.DATASET,
                        ArtifactStore.DATASET_OBJECT_PREFIX + declared.name() + ".csv", "text/csv"),
                slice.getBytes(StandardCharsets.UTF_8));
        results.recordArtifact(stored);

        return new DatasetDelivery(engine.datasetFileName(declared.name()), stored.objectKey());
    }

    private Optional<SecretFileDelivery> prepareSessionFile(TestRun run,
            PerformanceTestSpecification specification, RunnerShard shard,
            List<RuntimeCredential> runtimeCredentials, Duration deadline, Instant now) {

        Optional<PerformanceTestSpecification.AuthenticationSpec> sessionAuth =
                sessionAuthentication(specification);
        if (sessionAuth.isEmpty()) {
            return Optional.empty();
        }

        PerformanceTestSpecification.AuthenticationSpec auth = sessionAuth.get();
        RuntimeCredential seed = runtimeCredentials.stream()
                .filter(credential -> credential.name().equals(auth.name()))
                .findFirst()
                .orElse(null);

        SessionPoolProvider.SessionPool pool = sessionPools.acquire(
                new SessionPoolProvider.SessionPoolRequest(run.id(), auth.name(), seed,
                        specification.peakProtocolUsers(), deadline, now));

        Instant mustRemainValidUntil = now.plus(deadline);
        if (pool.earliestExpiry().isBefore(mustRemainValidUntil)) {
            throw new SessionUnavailableException(
                    "Acquired sessions expire before this run's execution deadline. Refresh or "
                            + "supply a longer-lived session credential before starting the run.");
        }

        StringBuilder csv = new StringBuilder(sessionCsvHeader());
        for (SessionPoolProvider.SessionLease lease : partition(pool.leases(), shard)) {
            csv.append("%s,%s,%s%n".formatted(
                    csvEscape(lease.sessionId()), csvEscape(lease.value()), lease.expiresAt()));
        }

        return Optional.of(new SecretFileDelivery(
                SESSION_DATASET_FILE_NAME,
                csv.toString().getBytes(StandardCharsets.UTF_8),
                pool.earliestExpiry(),
                mustRemainValidUntil));
    }

    private static Optional<PerformanceTestSpecification.AuthenticationSpec> sessionAuthentication(
            PerformanceTestSpecification specification) {
        if (specification.spec() == null) {
            return Optional.empty();
        }

        Map<String, PerformanceTestSpecification.AuthenticationSpec> authByName =
                specification.spec().authentication().stream()
                        .filter(auth -> auth.name() != null)
                        .collect(Collectors.toMap(
                                PerformanceTestSpecification.AuthenticationSpec::name,
                                java.util.function.Function.identity(),
                                (left, right) -> left,
                                LinkedHashMap::new));

        return specification.spec().workloads().stream()
                .map(PerformanceTestSpecification.WorkloadSpec::authRef)
                .filter(ref -> ref != null && !ref.isBlank())
                .map(authByName::get)
                .filter(Objects::nonNull)
                .filter(RunWorkloadService::isRunSessionAuth)
                .findFirst();
    }

    public static boolean isRunSessionAuth(
            PerformanceTestSpecification.AuthenticationSpec authentication) {
        if (authentication == null || authentication.secretRef() == null) {
            return false;
        }
        return "run://session".equalsIgnoreCase(authentication.secretRef().trim());
    }

    private static List<SessionPoolProvider.SessionLease> partition(
            List<SessionPoolProvider.SessionLease> leases, RunnerShard shard) {
        List<SessionPoolProvider.SessionLease> partition = new ArrayList<>();
        for (int index = 0; index < leases.size(); index++) {
            if (index % shard.shardCount() == shard.shardIndex()) {
                partition.add(leases.get(index));
            }
        }
        return partition;
    }

    private static String sessionCsvHeader() {
        return "session_id,session_value,session_expires_at\n";
    }

    private static String csvEscape(String value) {
        String escaped = value.replace("\"", "\"\"");
        return "\"" + escaped + "\"";
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
    public RunAnalyzer.Analysis analyze(TestRun run, List<ExecutionPlan> plans,
            DefinitionVersion version, List<RunnerShard> shards) {

        if (plans.isEmpty()) {
            throw new IllegalArgumentException("A run cannot be analyzed without its plans");
        }

        // Adopt anything a remote runner uploaded before looking for results. A Kubernetes runner
        // writes to object storage and cannot write to the database — it has no credentials for one
        // and must never have any (a provider that could write run state could mark its own failures
        // as successes). Without this step a Job's output would be invisible to analysis.
        adoptUploadedArtifacts(run, plans);

        // Each workload's results are parsed by its own engine and the samples are merged. That is
        // the whole of cross-engine analysis: a browser journey duration and an HTTP response time
        // are both a labelled latency by the time they leave their parser, which is what Sample was
        // designed for. Objectives are then judged once, over everything, and each is scoped to the
        // workload or operation it names.
        SampleSet samples = SampleSet.empty();
        int expected = 0;
        int reported = 0;

        for (ExecutionPlan plan : plans) {
            ExecutionEngineProvider engine = engines.require(plan.engine());

            List<RunnerOutput.ShardResult> shardResults = new ArrayList<>();
            for (RunnerShard shard : shards) {
                if (!plan.workloadName().equals(shard.workloadName())) {
                    continue;
                }
                resultDataFor(run.id(), shard).ifPresent(data ->
                        shardResults.add(new RunnerOutput.ShardResult(shard.shardIndex(), data)));
            }

            int planShards = Math.max(1, plan.capacity().shardCount());
            expected += planShards;
            reported += shardResults.size();

            samples = samples.merge(engine.parse(
                    new RunnerOutput(run.id(), planShards, shardResults)));
        }

        final int shardsExpected = expected;
        final int shardsReported = reported;

        RunAnalyzer.Analysis measured = analyzer.analyze(run.id(), engineLabel(plans),
                shardsExpected, shardsReported, samples, objectives(version.specification()));

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

        log.info("Run analyzed runId={} workloads={} shardsReported={}/{} samples={} unreadable={} "
                        + "objectives={} successful={} synthetic={}",
                run.id(), plans.size(), shardsReported, shardsExpected, samples.samples().size(),
                samples.unparsedRows(), analysis.outcomes().size(), analysis.isSuccessful(),
                run.synthetic());

        return analysis;
    }

    /**
     * What to record as the engine behind a run's measurements.
     *
     * <p>One engine's identifier when a run has one, and every engine joined when it has several.
     * Not the first engine's: a hybrid run's numbers come from two engines, and a stored result
     * naming only one would be read months later as though a browser cohort had never been part of
     * it. The per-operation rows are where the detail lives; this is the honest summary label.
     */
    private static String engineLabel(List<ExecutionPlan> plans) {
        return plans.stream()
                .map(ExecutionPlan::engine)
                .distinct()
                .sorted()
                .collect(Collectors.joining("+"));
    }

    /**
     * Reads every shard's published snapshot, merges them, and records the result.
     *
     * <p>Called on each pass while a run executes. It is the whole of live monitoring on the control
     * plane's side: a runner publishes into object storage, this reads what is there, and the merge
     * becomes a durable point in the run's series.
     *
     * <p><b>Nothing here can fail a run.</b> Storage briefly unreadable, a truncated object caught
     * mid-overwrite, a snapshot from a runner image whose document this version does not know — all
     * of them cost a point on a chart, and none of them may stop a run reaching a terminal state.
     * That asymmetry is deliberate: live progress is a convenience laid over the run lifecycle, and
     * a convenience that can break the lifecycle is a liability.
     *
     * <p>Snapshots whose identity does not match are discarded rather than merged, for the same
     * reason results are: a stale object left by an earlier run under a reused key would otherwise
     * contribute real-looking numbers to the wrong run's live view, where nothing would ever look
     * wrong about them.
     *
     * @return the merged view, or empty when no shard has published anything readable yet
     */
    public Optional<LiveProgress> recordProgress(TestRun run, List<ExecutionPlan> plans,
            Instant now) {
        List<ArtifactReference> published;
        try {
            published = artifacts.listForRun(run.id()).stream()
                    .filter(reference -> reference.type() == ArtifactType.PROGRESS)
                    .toList();
        } catch (RuntimeException e) {
            log.debug("Could not list progress snapshots runId={}", run.id(), e);
            return Optional.empty();
        }

        // A snapshot has to belong to one of this run's plans. A hybrid run's generators publish
        // against different plans, so matching a single digest would silently discard every
        // snapshot from the second workload — and the live view would show half the traffic.
        Set<String> expectedDigests = plans.stream()
                .map(plan -> plan.planDigest().value())
                .collect(Collectors.toSet());
        List<ShardProgress> snapshots = new ArrayList<>();

        for (ArtifactReference reference : published) {
            try {
                artifacts.read(reference)
                        .flatMap(progressCodec::decode)
                        .filter(snapshot -> {
                            boolean matches = expectedDigests.stream()
                                    .anyMatch(digest -> snapshot.describes(run.id(), digest));
                            if (!matches) {
                                log.warn("Discarding a progress snapshot that describes another run "
                                                + "or plan runId={} object={}",
                                        run.id(), reference.objectKey());
                            }
                            return matches;
                        })
                        .ifPresent(snapshots::add);
            } catch (RuntimeException e) {
                log.debug("A progress snapshot could not be read runId={} object={}",
                        run.id(), reference.objectKey(), e);
            }
        }

        int expectedShards = plans.stream()
                .mapToInt(plan -> Math.max(1, plan.capacity().shardCount()))
                .sum();

        Optional<LiveProgress> merged = LiveProgress.merge(snapshots, expectedShards, now);

        merged.ifPresent(view -> {
            try {
                progress.record(view);
            } catch (RuntimeException e) {
                log.warn("Could not record live progress runId={}", run.id(), e);
            }
        });

        return merged;
    }

    public Optional<LiveProgress> latestProgress(TestRun run) {
        return progress.latest(run.id());
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
        specification.spec().workloads().forEach(workload -> {
            if (workload.type() == WorkloadType.BROWSER && workload.name() != null) {
                labels.add(workload.name());
            }
            workload.scenarios().forEach(scenario ->
                    labels.add(workload.name() + "/" + scenario.name()));
        });
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
    private void adoptUploadedArtifacts(TestRun run, List<ExecutionPlan> plans) {
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
            if (reference.type() == ArtifactType.DATASET) {
                continue;
            }
            // A progress snapshot is overwritten in place every few seconds, so a digest recorded
            // for it stops matching the object almost immediately — and the download path refuses
            // content whose digest does not match what was recorded. The durable record of what a
            // run was doing is its merged series, not this object.
            if (reference.type() == ArtifactType.PROGRESS) {
                continue;
            }
            results.recordArtifact(reference);
            adopted++;
        }

        if (adopted > 0) {
            log.info("Adopted {} runner-uploaded artifacts runId={} teamId={}",
                    adopted, run.id(), run.teamId());
        }

        verifyPlanIdentity(run, plans);
    }

    /**
     * Checks every runner manifest against the plan the run actually references.
     *
     * <p>A manifest that carries no digest is not treated as a mismatch — an older runner, or one
     * whose upload was cut short, has said nothing rather than said something wrong. A manifest that
     * carries a <em>different</em> digest is refused outright.
     */
    private void verifyPlanIdentity(TestRun run, List<ExecutionPlan> plans) {
        // Any of the run's plans, because a hybrid run's manifests come from different runners
        // executing different plans. Membership rather than equality is still the same check it
        // always was: a manifest whose digest belongs to none of this run's plans describes a test
        // this run did not commission.
        Set<String> expected = plans.stream()
                .map(plan -> plan.planDigest().value())
                .collect(Collectors.toSet());

        for (ArtifactReference reference : results.artifactsFor(run.id())) {
            if (reference.type() != ArtifactType.RUNNER_MANIFEST) {
                continue;
            }
            Optional<String> reported = artifacts.read(reference)
                    .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                    .flatMap(RunWorkloadService::planDigestIn);

            if (reported.isPresent() && !expected.contains(reported.get())) {
                log.error("Plan digest mismatch runId={} expected one of={} reported={} artifact={}",
                        run.id(), expected, reported.get(), reference.objectKey());
                throw new PlanDigestMismatchException(run.id(),
                        String.join(" or ", expected), reported.get());
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
