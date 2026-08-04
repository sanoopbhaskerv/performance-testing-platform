package com.perfplatform.orchestration.run;

import com.perfplatform.domain.identity.ExecutionPlanId;
import com.perfplatform.domain.identity.RunnerShardId;
import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.identity.TestDefinitionVersionId;
import com.perfplatform.domain.identity.TestRunId;
import com.perfplatform.domain.plan.ExecutionPlan;
import com.perfplatform.domain.port.ArtifactStore;
import com.perfplatform.domain.port.DefinitionVersionCatalog.DefinitionVersion;
import com.perfplatform.domain.port.RunAnalyzer;
import com.perfplatform.domain.results.NormalizedRunResult;
import com.perfplatform.domain.results.ObjectiveOutcome;
import com.perfplatform.domain.results.Sample;
import com.perfplatform.domain.results.SampleSet;
import com.perfplatform.domain.run.RunnerShard;
import com.perfplatform.domain.run.TestRun;
import com.perfplatform.domain.spec.PerformanceTestSpecification;
import com.perfplatform.domain.spec.SpecificationDigest;
import com.perfplatform.engine.spi.EngineCapabilities;
import com.perfplatform.engine.spi.EnginePlan;
import com.perfplatform.engine.spi.ExecutionEngineProvider;
import com.perfplatform.engine.spi.RenderedWorkload;
import com.perfplatform.engine.spi.RunnerOutput;
import com.perfplatform.engine.spi.ShardAssignment;
import com.perfplatform.engine.spi.WorkloadKind;
import com.perfplatform.domain.target.TargetAddress;
import com.perfplatform.orchestration.plan.EngineRegistry;
import com.perfplatform.orchestration.support.OrchestrationFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Analysing a run whose workloads ran on different engines.
 *
 * <p>The third of the structural changes S9 made. Each workload's results are parsed by its own
 * engine and the samples are merged before objectives are judged — which works because a browser
 * journey duration and an HTTP response time are both a labelled latency by the time they leave a
 * parser. That is what {@code Sample} was designed for, and this is the first thing to rely on it.
 *
 * <p>The alternative — analysing each workload separately and merging verdicts — was rejected
 * because an objective scoped to the whole test ({@code scope: *}) would then have no single result
 * to be judged against, and the platform would have had to invent a rule for combining two verdicts.
 */
class MultiWorkloadAnalysisTest {

    private static final Instant NOW = Instant.parse("2026-08-04T10:00:00Z");

    private final TeamId team = TeamId.generate();
    private final TestRunId runId = TestRunId.generate();
    private final TestDefinitionVersionId versionId = TestDefinitionVersionId.generate();

    private final OrchestrationFixtures.InMemoryArtifacts artifacts =
            new OrchestrationFixtures.InMemoryArtifacts();

    /**
     * An engine that reads its own format and nothing else.
     *
     * <p>Two of these stand in for JMeter and Playwright. Each parses only the rows it recognises,
     * so a test in which one engine silently read the other's output would fail — which is the
     * mistake worth guarding against, since both formats are line-oriented text.
     */
    private static ExecutionEngineProvider engine(String engineId, String prefix, WorkloadKind kind) {
        return new ExecutionEngineProvider() {
            @Override
            public String engineId() {
                return engineId;
            }

            @Override
            public EngineCapabilities capabilities() {
                return new EngineCapabilities(engineId, Set.of(kind), Set.of("manual"),
                        Set.of("stages"), 100, false, false, "stub/%s:1".formatted(engineId));
            }

            @Override
            public com.perfplatform.domain.validation.ValidationResult validate(
                    PerformanceTestSpecification specification) {
                return com.perfplatform.domain.validation.ValidationResult.valid();
            }

            @Override
            public EnginePlan compile(PerformanceTestSpecification specification,
                    String workloadName) {
                return new EnginePlan("{\"engine\":\"" + engineId + "\",\"workload\":\""
                        + workloadName + "\"}", 1, 10, 300);
            }

            @Override
            public RenderedWorkload render(PerformanceTestSpecification specification,
                    ExecutionPlan plan, ShardAssignment assignment, TargetAddress target) {
                return new RenderedWorkload("shard.stub", "text/plain", "stub");
            }

            @Override
            public SampleSet parse(RunnerOutput output) {
                List<Sample> samples = new ArrayList<>();
                for (RunnerOutput.ShardResult shard : output.shardResults()) {
                    for (String line : shard.resultData().split("\n")) {
                        if (!line.startsWith(prefix)) {
                            continue;
                        }
                        String[] parts = line.split(",");
                        samples.add(new Sample(parts[1], Long.parseLong(parts[2]), true,
                                NOW.toEpochMilli()));
                    }
                }
                return new SampleSet(samples, 0);
            }
        };
    }

    private ExecutionPlan plan(String workloadName, String engineId) {
        String planJson = "{\"engine\":\"" + engineId + "\",\"workload\":\"" + workloadName + "\"}";
        return new ExecutionPlan(ExecutionPlanId.generate(), versionId, workloadName, engineId,
                planJson, SpecificationDigest.ofCanonicalForm(planJson),
                new ExecutionPlan.CapacityEstimate(1, 10, 300), NOW);
    }

    private RunnerShard shard(String workloadName, String engineId) {
        return RunnerShard.planned(RunnerShardId.generate(), runId, engineId, workloadName, 0, 1);
    }

    /** Stores a shard's results as the runner would have uploaded them. */
    private void uploadResults(RunnerShard shard, String content) {
        artifacts.uploadedByRunner(new ArtifactStore.ArtifactDescriptor(
                        team, runId, java.util.Optional.of(shard.id()),
                        ArtifactStore.ArtifactType.RESULTS, "results.csv", "text/csv"),
                content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("both workloads' samples reach one result, each under its own operation name")
    void mergesSamplesAcrossEngines() {
        RunWorkloadService workloads = new RunWorkloadService(
                new EngineRegistry(List.of(
                        engine("jmeter", "http", WorkloadKind.PROTOCOL),
                        engine("playwright", "browser", WorkloadKind.BROWSER))),
                artifacts, artifacts, new RecordingAnalyzer(),
                OrchestrationFixtures.fixedTarget(),
                new OrchestrationFixtures.InMemoryDatasets(),
                new OrchestrationFixtures.InMemoryDatasets(),
                new RuntimeCredentialSessionPoolProvider(),
                new OrchestrationFixtures.InMemoryProgress());

        RunnerShard protocolShard = shard("api-load", "jmeter");
        RunnerShard browserShard = shard("browser-journey", "playwright");

        uploadResults(protocolShard, "http,api-load/search,120\nhttp,api-load/search,140\n");
        uploadResults(browserShard, "browser,browser-journey,3400\n");

        TestRun run = TestRun.request(runId, team, versionId, "performance",
                new TestRun.TriggerContext("nightly", "sha256:abc", "alice@example.com"), NOW).run();

        RunAnalyzer.Analysis analysis = workloads.analyze(
                run,
                List.of(plan("api-load", "jmeter"), plan("browser-journey", "playwright")),
                version(),
                List.of(protocolShard, browserShard));

        NormalizedRunResult result = analysis.result();

        // Three samples in one result: two HTTP and one browser journey, each keeping the operation
        // name its own engine gave it, so an objective scoped to either is judged against its own
        // traffic.
        assertThat(result.overall().sampleCount()).isEqualTo(3);
        assertThat(result.operationNames())
                .containsExactlyInAnyOrder("api-load/search", "browser-journey");

        // Every shard of every plan counted. A run reporting two of two shards is complete; one
        // that counted only the first plan's would report a partial result as whole.
        assertThat(result.shardsExpected()).isEqualTo(2);
        assertThat(result.shardsReported()).isEqualTo(2);
    }

    @Test
    @DisplayName("the stored result names every engine behind it, not just the first")
    void namesEveryEngine() {
        RecordingAnalyzer analyzer = new RecordingAnalyzer();
        RunWorkloadService workloads = new RunWorkloadService(
                new EngineRegistry(List.of(
                        engine("jmeter", "http", WorkloadKind.PROTOCOL),
                        engine("playwright", "browser", WorkloadKind.BROWSER))),
                artifacts, artifacts, analyzer, OrchestrationFixtures.fixedTarget(),
                new OrchestrationFixtures.InMemoryDatasets(),
                new OrchestrationFixtures.InMemoryDatasets(),
                new RuntimeCredentialSessionPoolProvider(),
                new OrchestrationFixtures.InMemoryProgress());

        RunnerShard protocolShard = shard("api-load", "jmeter");
        RunnerShard browserShard = shard("browser-journey", "playwright");
        uploadResults(protocolShard, "http,api-load/search,120\n");
        uploadResults(browserShard, "browser,browser-journey,3400\n");

        TestRun run = TestRun.request(runId, team, versionId, "performance",
                new TestRun.TriggerContext("nightly", "sha256:abc", "alice@example.com"), NOW).run();

        workloads.analyze(run,
                List.of(plan("api-load", "jmeter"), plan("browser-journey", "playwright")),
                version(), List.of(protocolShard, browserShard));

        // Read months later, a stored result naming only "jmeter" would say a browser cohort had
        // never been part of this run.
        assertThat(analyzer.engine).isEqualTo("jmeter+playwright");
    }

    private DefinitionVersion version() {
        return new DefinitionVersion(versionId,
                com.perfplatform.domain.identity.TestDefinitionId.generate(), team, 1,
                SpecificationDigest.of(OrchestrationFixtures.apiSpecification()),
                OrchestrationFixtures.apiSpecification(), true);
    }

    /** Captures what analysis was asked to judge, which is the thing under test here. */
    private static final class RecordingAnalyzer implements RunAnalyzer {

        private String engine;

        @Override
        public Analysis analyze(TestRunId runId, String engine, int shardsExpected,
                int shardsReported, SampleSet samples,
                List<PerformanceTestSpecification.ObjectiveSpec> objectives) {

            this.engine = engine;

            // A minimal reduction rather than the real one: orchestration cannot depend on
            // platform-results (the analyzer is a port precisely so it does not), and what this
            // test asserts is which samples and which shard counts arrived — not how a percentile
            // is computed.
            java.util.Map<String, com.perfplatform.domain.results.OperationMetrics> byOperation =
                    new java.util.LinkedHashMap<>();
            samples.samples().stream()
                    .collect(java.util.stream.Collectors.groupingBy(Sample::operation,
                            java.util.LinkedHashMap::new, java.util.stream.Collectors.counting()))
                    .forEach((operation, count) -> byOperation.put(operation,
                            new com.perfplatform.domain.results.OperationMetrics(
                                    operation, count, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)));

            return new Analysis(
                    new NormalizedRunResult(runId, engine,
                            java.util.Optional.empty(), java.util.Optional.empty(),
                            shardsExpected, shardsReported,
                            new com.perfplatform.domain.results.OperationMetrics(
                                    com.perfplatform.domain.results.OperationMetrics.ALL,
                                    samples.samples().size(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                            byOperation),
                    List.of());
        }

        @Override
        public List<ObjectiveOutcome> judge(NormalizedRunResult result,
                List<PerformanceTestSpecification.ObjectiveSpec> objectives) {
            return List.of();
        }
    }
}
