package com.perfplatform.orchestration.support;

import com.perfplatform.domain.execution.ExecutionProvider;
import com.perfplatform.domain.execution.ProvisionRequest;
import com.perfplatform.domain.identity.ArtifactId;
import com.perfplatform.domain.identity.ExecutionPlanId;
import com.perfplatform.domain.identity.RunnerShardId;
import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.identity.TestDefinitionId;
import com.perfplatform.domain.identity.TestDefinitionVersionId;
import com.perfplatform.domain.identity.TestRunId;
import com.perfplatform.domain.plan.ExecutionPlan;
import com.perfplatform.domain.port.DefinitionVersionCatalog;
import com.perfplatform.domain.port.ArtifactStore;
import com.perfplatform.domain.port.ExecutionPlanRepository;
import com.perfplatform.domain.port.ExecutionPlanRepository.ImmutablePlanException;
import com.perfplatform.domain.port.ResultCatalog;
import com.perfplatform.domain.port.RunAnalyzer;
import com.perfplatform.domain.port.RunnerShardRepository;
import com.perfplatform.domain.port.TargetResolver;
import com.perfplatform.domain.results.NormalizedRunResult;
import com.perfplatform.domain.results.ObjectiveOutcome;
import com.perfplatform.domain.results.Sample;
import com.perfplatform.domain.results.SampleSet;
import com.perfplatform.domain.target.TargetAddress;
import com.perfplatform.engine.spi.EngineCapabilities;
import com.perfplatform.engine.spi.EnginePlan;
import com.perfplatform.engine.spi.ExecutionEngineProvider;
import com.perfplatform.engine.spi.RenderedWorkload;
import com.perfplatform.engine.spi.RunnerOutput;
import com.perfplatform.engine.spi.ShardAssignment;
import com.perfplatform.engine.spi.WorkloadKind;
import com.perfplatform.domain.run.RunnerShard;
import com.perfplatform.domain.security.CallerContext;
import com.perfplatform.domain.security.Scope;
import com.perfplatform.domain.spec.LoadModelType;
import com.perfplatform.domain.spec.PerformanceTestSpecification;
import com.perfplatform.domain.spec.PerformanceTestSpecification.EnvironmentSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.LoadModel;
import com.perfplatform.domain.spec.PerformanceTestSpecification.LoadStage;
import com.perfplatform.domain.spec.PerformanceTestSpecification.ScenarioSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.SpecMetadata;
import com.perfplatform.domain.spec.PerformanceTestSpecification.TestSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.WorkloadSpec;
import com.perfplatform.domain.spec.SpecificationDigest;
import com.perfplatform.domain.spec.TargetClass;
import com.perfplatform.domain.spec.WorkloadType;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Fixtures and in-memory port implementations for orchestration tests.
 *
 * <p>Built here rather than shared from the domain's test sources so that orchestration's tests do
 * not depend on another module's test artifacts — a dependency that makes both harder to change.
 */
public final class OrchestrationFixtures {

    public static final String ENVIRONMENT = "performance";

    private OrchestrationFixtures() {
    }

    /** A protocol-only specification with two weighted scenarios and a staged model. */
    public static PerformanceTestSpecification apiSpecification() {
        return new PerformanceTestSpecification(
                PerformanceTestSpecification.SUPPORTED_API_VERSION,
                PerformanceTestSpecification.SUPPORTED_KIND,
                new SpecMetadata("proposal-baseline", "advice", "proposal", Map.of()),
                new TestSpec(
                        new EnvironmentSpec(ENVIRONMENT, "advice-perf", TargetClass.NON_PRODUCTION),
                        List.of(), List.of(), List.of(),
                        List.of(new WorkloadSpec(
                                "api-load", WorkloadType.PROTOCOL, "jmeter", null,
                                new LoadModel(LoadModelType.STAGES, List.of(
                                        new LoadStage(Duration.ofMinutes(5), 100),
                                        new LoadStage(Duration.ofMinutes(10), 200))),
                                List.of(
                                        new ScenarioSpec("search", 70, "searchClient"),
                                        new ScenarioSpec("open", 30, "getProposal")),
                                null, null, null)),
                        List.of(), null, null));
    }

    /** A caller who may do everything for the given team, and nothing for any other. */
    public static CallerContext caller(TeamId teamId) {
        return new CallerContext("alice@example.com", Set.of(teamId),
                Set.of(Scope.RUNS_EXECUTE, Scope.RUNS_CANCEL, Scope.RUNS_READ), false);
    }

    /** A catalog holding versions supplied by the test. */
    public static final class StubDefinitionVersionCatalog implements DefinitionVersionCatalog {

        private final Map<TestDefinitionVersionId, DefinitionVersion> versions = new HashMap<>();

        /** Registers an executable version owned by the given team. */
        public DefinitionVersion add(TeamId teamId) {
            return add(teamId, true);
        }

        public DefinitionVersion add(TeamId teamId, boolean executable) {
            return add(teamId, apiSpecification(), executable);
        }

        public DefinitionVersion add(TeamId teamId, PerformanceTestSpecification specification,
                boolean executable) {
            DefinitionVersion version = new DefinitionVersion(
                    TestDefinitionVersionId.generate(),
                    TestDefinitionId.generate(),
                    teamId,
                    1,
                    SpecificationDigest.of(specification),
                    specification,
                    executable);
            versions.put(version.id(), version);
            return version;
        }

        /** Forgets a version, simulating one that became unreadable between passes. */
        public void remove(TestDefinitionVersionId id) {
            versions.remove(id);
        }

        @Override
        public Optional<DefinitionVersion> find(TestDefinitionVersionId id) {
            return Optional.ofNullable(versions.get(id));
        }
    }

    /** An in-memory shard repository. */
    public static final class InMemoryShardRepository implements RunnerShardRepository {

        private final Map<RunnerShardId, RunnerShard> shards = new HashMap<>();

        @Override
        public Optional<RunnerShard> find(RunnerShardId id) {
            return Optional.ofNullable(shards.get(id));
        }

        @Override
        public List<RunnerShard> findByRun(TestRunId runId) {
            List<RunnerShard> found = new ArrayList<>(shards.values().stream()
                    .filter(shard -> shard.runId().equals(runId))
                    .toList());
            found.sort((left, right) -> Integer.compare(left.shardIndex(), right.shardIndex()));
            return found;
        }

        @Override
        public RunnerShard save(RunnerShard shard) {
            shards.put(shard.id(), shard);
            return shard;
        }
    }

    /**
     * An in-memory plan repository that refuses to overwrite a stored plan.
     *
     * <p>Keeps the run association, because that is what makes a run's plans findable — the same
     * thing the real store's {@code run_id} column does, and what a reconciler relies on to avoid
     * compiling a second set after a crash.
     */
    public static final class InMemoryPlanRepository implements ExecutionPlanRepository {

        private final Map<ExecutionPlanId, ExecutionPlan> plans = new HashMap<>();
        private final Map<ExecutionPlanId, TestRunId> runs = new HashMap<>();

        @Override
        public Optional<ExecutionPlan> find(ExecutionPlanId id) {
            return Optional.ofNullable(plans.get(id));
        }

        @Override
        public List<ExecutionPlan> findByRun(TestRunId runId) {
            return plans.values().stream()
                    .filter(plan -> runId.equals(runs.get(plan.id())))
                    .sorted(java.util.Comparator.comparing(ExecutionPlan::workloadName))
                    .toList();
        }

        @Override
        public ExecutionPlan save(TestRunId runId, ExecutionPlan plan) {
            if (plans.containsKey(plan.id())) {
                throw new ImmutablePlanException(plan.id());
            }
            plans.put(plan.id(), plan);
            runs.put(plan.id(), runId);
            return plan;
        }

        public int count() {
            return plans.size();
        }
    }

    /**
     * An artifact store and catalog in memory.
     *
     * <p>One class, but <b>two genuinely separate collections</b>, exactly as production has two
     * systems: object storage holds content, PostgreSQL holds the record of it. Conflating them
     * would hide the situation a remote runner creates — content that exists while the database
     * knows nothing about it — which is precisely what artifact adoption exists to resolve.
     */
    public static final class InMemoryArtifacts implements ArtifactStore, ResultCatalog {

        /** Object storage: content by key. */
        private final Map<String, byte[]> content = new HashMap<>();

        /** Object storage: what is actually there, whether or not the catalog knows. */
        private final List<ArtifactReference> stored = new ArrayList<>();

        /** The catalog: what the platform has recorded. Deliberately not the same list. */
        private final List<ArtifactReference> catalogued = new ArrayList<>();

        private final Map<TestRunId, List<ObjectiveOutcome>> outcomes = new HashMap<>();
        private final Map<TestRunId, NormalizedRunResult> metrics = new HashMap<>();

        @Override
        public ArtifactReference store(ArtifactDescriptor descriptor, byte[] body) {
            String key = "teams/%s/runs/%s/%s/%s".formatted(descriptor.teamId().value(),
                    descriptor.runId().value(),
                    descriptor.shardId().map(id -> "shards/" + id.value()).orElse("run"),
                    descriptor.fileName());
            return put(key, descriptor, body);
        }

        /**
         * Content appearing in storage with nothing recorded in the catalog.
         *
         * <p>What a Kubernetes runner leaves behind: it uploads directly and has no database access
         * at all. Reconciliation has to notice.
         */
        public ArtifactReference uploadedByRunner(ArtifactDescriptor descriptor, byte[] body) {
            String key = "teams/%s/runs/%s/shards/%s/%s".formatted(descriptor.teamId().value(),
                    descriptor.runId().value(),
                    descriptor.shardId().map(id -> id.value().toString()).orElse("run"),
                    descriptor.fileName());
            return put(key, descriptor, body);
        }

        private ArtifactReference put(String key, ArtifactDescriptor descriptor, byte[] body) {
            content.put(key, body == null ? new byte[0] : body);
            ArtifactReference reference = new ArtifactReference(ArtifactId.generate(),
                    descriptor.runId(), descriptor.shardId(), descriptor.type(), key,
                    "sha256:test", descriptor.contentType(), body == null ? 0 : body.length,
                    java.time.Instant.parse("2026-07-30T10:00:00Z"));

            stored.removeIf(existing -> existing.objectKey().equals(key));
            stored.add(reference);
            return reference;
        }

        @Override
        public Optional<byte[]> read(ArtifactReference reference) {
            return Optional.ofNullable(content.get(reference.objectKey()));
        }

        @Override
        public void delete(ArtifactReference reference) {
            content.remove(reference.objectKey());
            stored.removeIf(existing -> existing.objectKey().equals(reference.objectKey()));
        }

        @Override
        public List<ArtifactReference> listForRun(TestRunId runId) {
            return stored.stream().filter(r -> r.runId().equals(runId)).toList();
        }

        /** Deduplicates by object key, as the real catalog does, so adoption stays idempotent. */
        @Override
        public void recordArtifact(ArtifactReference reference) {
            if (catalogued.stream().noneMatch(r -> r.objectKey().equals(reference.objectKey()))) {
                catalogued.add(reference);
            }
        }

        @Override
        public List<ArtifactReference> artifactsFor(TestRunId runId) {
            return catalogued.stream().filter(r -> r.runId().equals(runId)).toList();
        }

        /** Replaces both, as the store does, so re-analysis in a test behaves as it does in production. */
        @Override
        public void recordAnalysis(TestRunId runId, NormalizedRunResult result,
                List<ObjectiveOutcome> recorded) {
            metrics.put(runId, result);
            outcomes.put(runId, List.copyOf(recorded));
        }

        @Override
        public List<ObjectiveOutcome> objectiveOutcomesFor(TestRunId runId) {
            return outcomes.getOrDefault(runId, List.of());
        }

        @Override
        public Optional<NormalizedRunResult> runMetricsFor(TestRunId runId) {
            return Optional.ofNullable(metrics.get(runId));
        }
    }

    /**
     * A run's live snapshots, in memory.
     *
     * <p>Keeps the same append-and-deduplicate contract as the real store, because the behaviour a
     * test most often needs to assert about progress is that a repeated reconciliation pass does
     * <em>not</em> add a second point.
     */
    public static final class InMemoryProgress
            implements com.perfplatform.domain.port.ProgressCatalog {

        private final Map<TestRunId, List<com.perfplatform.domain.progress.LiveProgress>> series =
                new HashMap<>();

        @Override
        public void record(com.perfplatform.domain.progress.LiveProgress progress) {
            List<com.perfplatform.domain.progress.LiveProgress> points =
                    series.computeIfAbsent(progress.runId(), key -> new ArrayList<>());
            boolean known = points.stream()
                    .anyMatch(point -> point.capturedAt().equals(progress.capturedAt()));
            if (!known) {
                points.add(progress);
            }
        }

        @Override
        public Optional<com.perfplatform.domain.progress.LiveProgress> latest(TestRunId runId) {
            List<com.perfplatform.domain.progress.LiveProgress> points =
                    series.getOrDefault(runId, List.of());
            return points.isEmpty()
                    ? Optional.empty()
                    : Optional.of(points.get(points.size() - 1));
        }

        @Override
        public List<com.perfplatform.domain.progress.LiveProgress> series(TestRunId runId,
                int limit) {
            return List.copyOf(series.getOrDefault(runId, List.of()));
        }

        @Override
        public void clear(TestRunId runId) {
            series.remove(runId);
        }
    }

    /**
     * Dataset content and its record, in memory.
     *
     * <p>One class implementing both ports, as {@code InMemoryArtifacts} does, because a test that
     * has to keep two fakes agreeing about which versions exist is a test about the fakes.
     */
    public static final class InMemoryDatasets
            implements com.perfplatform.domain.port.DatasetStore,
                       com.perfplatform.domain.port.DatasetCatalog {

        private final Map<String, byte[]> content = new HashMap<>();
        private final List<DatasetVersion> versions = new ArrayList<>();

        @Override
        public StoredContent store(com.perfplatform.domain.identity.TeamId teamId,
                com.perfplatform.domain.dataset.DatasetUri uri, byte[] body) {
            String key = "teams/%s/datasets/%s/%d/data.csv"
                    .formatted(teamId.value(), uri.name(), uri.version());
            content.put(key, body == null ? new byte[0] : body);
            return new StoredContent(key, "sha256:test", body == null ? 0 : body.length);
        }

        @Override
        public Optional<byte[]> read(String objectKey) {
            return Optional.ofNullable(content.get(objectKey));
        }

        @Override
        public DatasetVersion register(DatasetVersion version) {
            if (find(version.teamId(), version.name(), version.version()).isPresent()) {
                throw new DatasetVersionExistsException(version.uri());
            }
            versions.add(version);
            return version;
        }

        @Override
        public Optional<DatasetVersion> find(com.perfplatform.domain.identity.TeamId teamId,
                String name, int version) {
            return versions.stream()
                    .filter(stored -> stored.teamId().equals(teamId)
                            && stored.name().equals(name)
                            && stored.version() == version)
                    .findFirst();
        }

        @Override
        public List<DatasetVersion> listForTeam(com.perfplatform.domain.identity.TeamId teamId) {
            return versions.stream().filter(stored -> stored.teamId().equals(teamId)).toList();
        }

        @Override
        public int latestVersion(com.perfplatform.domain.identity.TeamId teamId, String name) {
            return versions.stream()
                    .filter(stored -> stored.teamId().equals(teamId) && stored.name().equals(name))
                    .mapToInt(DatasetVersion::version)
                    .max()
                    .orElse(0);
        }

        /** Uploads a dataset the way the upload use case would, so a run can resolve it. */
        public DatasetVersion given(com.perfplatform.domain.identity.TeamId teamId, String name,
                int version, String csv) {

            com.perfplatform.domain.dataset.DatasetUri uri =
                    com.perfplatform.domain.dataset.DatasetUri.of(name, version);
            StoredContent stored = store(teamId, uri, csv.getBytes(
                    java.nio.charset.StandardCharsets.UTF_8));

            return register(new DatasetVersion(
                    com.perfplatform.domain.identity.DatasetId.generate(), teamId, uri,
                    stored.objectKey(), stored.digest(),
                    com.perfplatform.domain.dataset.DatasetPartitioner.columnsOf(csv),
                    com.perfplatform.domain.dataset.DatasetPartitioner.partition(csv, 1)
                            .get(0).rows().size(),
                    stored.sizeBytes(), "fixture",
                    java.time.Instant.parse("2026-07-30T10:00:00Z")));
        }
    }

    /** A resolver that always returns the same permitted address. */
    public static TargetResolver fixedTarget() {
        return (teamId, applicationName, environment) ->
                Optional.of(TargetAddress.parse("http://localhost:8080"));
    }

    /** A resolver that resolves nothing, for testing the refusal path. */
    public static TargetResolver noTarget() {
        return (teamId, applicationName, environment) -> Optional.empty();
    }

    /**
     * A minimal engine, so orchestration can be tested without depending on a real one.
     *
     * <p>Orchestration must not depend on {@code engine-jmeter} (CLAUDE.md §9), so its tests cannot
     * use it. This implements the SPI just enough to exercise the control loop: it compiles a trivial
     * plan, renders a marker file, and parses the JTL shape the fake provider writes.
     */
    public static final class StubEngineProvider implements ExecutionEngineProvider {

        public static final String ENGINE_ID = "jmeter";

        @Override
        public String engineId() {
            return ENGINE_ID;
        }

        @Override
        public EngineCapabilities capabilities() {
            return new EngineCapabilities(ENGINE_ID, Set.of(WorkloadKind.PROTOCOL),
                    Set.of("manual"), Set.of("stages"), 500, true, true, "stub/engine:1.0.0");
        }

        @Override
        public com.perfplatform.domain.validation.ValidationResult validate(
                PerformanceTestSpecification specification) {
            return com.perfplatform.domain.validation.ValidationResult.valid();
        }

        @Override
        public EnginePlan compile(PerformanceTestSpecification specification, String workloadName) {
            // The workload name is in the plan body so two plans of one run are distinguishable by
            // digest. Without it a hybrid run's plans would be byte-identical, and a digest check
            // that cannot tell them apart is not a check.
            return new EnginePlan(
                    "{\"engine\":\"jmeter\",\"stub\":true,\"workload\":\"" + workloadName + "\"}", 1,
                    specification.peakProtocolUsers(), 900);
        }

        @Override
        public RenderedWorkload render(PerformanceTestSpecification specification,
                com.perfplatform.domain.plan.ExecutionPlan plan, ShardAssignment assignment,
                TargetAddress target) {
            return new RenderedWorkload("shard-%d.stub".formatted(assignment.shardIndex()),
                    "text/plain", "shard=" + assignment.shardIndex()
                    + " target=" + target.baseUrl()
                    + " correlation=" + assignment.correlationValue());
        }

        /** Reads the same CSV shape the fake provider writes: timeStamp, elapsed, label, success. */
        @Override
        public SampleSet parse(RunnerOutput output) {
            List<Sample> samples = new ArrayList<>();
            long unparsed = 0;

            for (RunnerOutput.ShardResult shard : output.shardResults()) {
                String[] lines = shard.resultData().split("\n");
                for (int i = 1; i < lines.length; i++) {
                    String[] fields = lines[i].split(",");
                    if (fields.length < 8) {
                        if (!lines[i].isBlank()) {
                            unparsed++;
                        }
                        continue;
                    }
                    try {
                        samples.add(new Sample(fields[2], Long.parseLong(fields[1]),
                                "true".equals(fields[7]), Long.parseLong(fields[0])));
                    } catch (RuntimeException e) {
                        unparsed++;
                    }
                }
            }
            return new SampleSet(samples, unparsed);
        }
    }

    /** Minimal browser engine for hybrid orchestration tests. */
    public static final class StubBrowserEngineProvider implements ExecutionEngineProvider {

        public static final String ENGINE_ID = "playwright";

        @Override
        public String engineId() {
            return ENGINE_ID;
        }

        @Override
        public EngineCapabilities capabilities() {
            return new EngineCapabilities(ENGINE_ID, Set.of(WorkloadKind.BROWSER),
                    Set.of("playwright"), Set.of(), 20, false, false, "stub/browser:1.0.0");
        }

        @Override
        public com.perfplatform.domain.validation.ValidationResult validate(
                PerformanceTestSpecification specification) {
            return com.perfplatform.domain.validation.ValidationResult.valid();
        }

        @Override
        public EnginePlan compile(PerformanceTestSpecification specification, String workloadName) {
            return new EnginePlan(
                    "{\"engine\":\"playwright\",\"stub\":true,\"workload\":\"" + workloadName + "\"}",
                    1, specification.peakBrowserSessions(), 300);
        }

        @Override
        public RenderedWorkload render(PerformanceTestSpecification specification,
                ExecutionPlan plan, ShardAssignment assignment, TargetAddress target) {
            return new RenderedWorkload("shard-%d-browser.stub".formatted(assignment.shardIndex()),
                    "text/plain", "browser=" + assignment.shardIndex()
                            + " target=" + target.baseUrl());
        }

        @Override
        public SampleSet parse(RunnerOutput output) {
            return SampleSet.empty();
        }
    }

    /**
     * A provider that behaves exactly like its delegate but declares that it generates real load.
     *
     * <p>Needed because {@link com.perfplatform.orchestration.execution.FakeExecutionProvider}
     * correctly and permanently declares that it generates none, which makes every run it executes
     * reach {@code FAILED}. That is the right production behaviour and the wrong fixture for a test
     * asking "does the control loop drive a run to a successful terminal state" — without this,
     * nothing would exercise the {@code COMPLETED} path at all.
     *
     * <p>Deliberately a test decorator rather than a flag on the fake provider. A switch that lets
     * the fake claim to generate load is a switch that eventually gets set in a configuration file,
     * and the whole point of §2 is that this particular lie must be impossible to tell.
     */
    public static ExecutionProvider declaringRealLoad(ExecutionProvider delegate) {
        return new ExecutionProvider() {
            @Override
            public String providerId() {
                return delegate.providerId();
            }

            @Override
            public boolean generatesRealLoad() {
                return true;
            }

            @Override
            public ProvisioningResult provision(ProvisionRequest request) {
                return delegate.provision(request);
            }

            @Override
            public ExecutionStatus inspect(RunnerShard.ExecutionHandle handle) {
                return delegate.inspect(handle);
            }

            @Override
            public CancellationResult cancel(RunnerShard.ExecutionHandle handle) {
                return delegate.cancel(handle);
            }
        };
    }

    /** An analyzer that summarizes nothing and passes everything, for control-flow tests. */
    public static RunAnalyzer permissiveAnalyzer() {
        return new RunAnalyzer() {
            @Override
            public Analysis analyze(TestRunId runId, String engine, int shardsExpected,
                    int shardsReported, com.perfplatform.domain.results.SampleSet samples,
                    List<com.perfplatform.domain.spec.PerformanceTestSpecification.ObjectiveSpec>
                            objectives) {
                return new Analysis(
                        new NormalizedRunResult(runId, engine, Optional.empty(), Optional.empty(),
                                shardsExpected, shardsReported,
                                com.perfplatform.domain.results.OperationMetrics.empty("*"),
                                Map.of()),
                        List.of());
            }

            @Override
            public List<ObjectiveOutcome> judge(NormalizedRunResult result,
                    List<com.perfplatform.domain.spec.PerformanceTestSpecification.ObjectiveSpec>
                            objectives) {
                return List.of();
            }
        };
    }
}
