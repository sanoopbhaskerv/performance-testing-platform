package com.perfplatform.engine.playwright;

import com.perfplatform.domain.plan.ExecutionPlan;
import com.perfplatform.domain.results.SampleSet;
import com.perfplatform.domain.spec.PerformanceTestSpecification;
import com.perfplatform.domain.spec.PerformanceTestSpecification.SourceSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.WorkloadSpec;
import com.perfplatform.domain.spec.WorkloadType;
import com.perfplatform.domain.spec.support.CanonicalJson;
import com.perfplatform.domain.target.TargetAddress;
import com.perfplatform.domain.validation.ValidationFinding;
import com.perfplatform.domain.validation.ValidationResult;
import com.perfplatform.engine.spi.EngineCapabilities;
import com.perfplatform.engine.spi.EnginePlan;
import com.perfplatform.engine.spi.ExecutionEngineProvider;
import com.perfplatform.engine.spi.RenderedWorkload;
import com.perfplatform.engine.spi.RunnerOutput;
import com.perfplatform.engine.spi.ShardAssignment;
import com.perfplatform.engine.spi.WorkloadKind;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Playwright provider for real-browser performance journeys.
 *
 * <p>S6 intentionally supports reviewed Playwright scripts stored as immutable platform artifacts.
 * Git checkout and dependency installation from repository coordinates are a later importer/runner
 * increment, because a moving dependency graph is too broad a production-readiness surface for this
 * first browser path.
 */
public final class PlaywrightEngineProvider implements ExecutionEngineProvider {

    /** Stable identifier persisted on execution plans and runner shards. */
    public static final String ENGINE_ID = "playwright";

    /** Pinned browser runner image. The registry prefix becomes internal before production use. */
    public static final String RUNNER_IMAGE = "perfplatform/playwright-runner:1.62.1-1";

    private static final String PLAN_VERSION = "v1alpha1";
    private static final int DEFAULT_SHARD_COUNT = 1;
    private static final int MAX_BROWSER_SESSIONS = 20;

    private static final EngineCapabilities CAPABILITIES = new EngineCapabilities(
            ENGINE_ID,
            Set.of(WorkloadKind.BROWSER),
            Set.of("playwright"),
            Set.of(),
            MAX_BROWSER_SESSIONS,
            false,
            false,
            RUNNER_IMAGE);

    @Override
    public String engineId() {
        return ENGINE_ID;
    }

    @Override
    public EngineCapabilities capabilities() {
        return CAPABILITIES;
    }

    @Override
    public ValidationResult validate(PerformanceTestSpecification specification) {
        List<ValidationFinding> findings = new ArrayList<>();
        List<WorkloadSpec> workloads = browserWorkloads(specification);

        if (workloads.isEmpty()) {
            findings.add(ValidationFinding.error("engine.playwright.noWorkload", "/spec/workloads",
                    "No browser workload is assigned to the Playwright engine.",
                    "Set engine: playwright on at least one browser workload."));
            return ValidationResult.of(findings);
        }

        for (int i = 0; i < workloads.size(); i++) {
            WorkloadSpec workload = workloads.get(i);
            String path = "/spec/workloads/" + i;
            if (workload.browserUsers() != null
                    && workload.browserUsers() > CAPABILITIES.maxConfiguredConcurrency()) {
                findings.add(ValidationFinding.error("engine.playwright.browserConcurrency",
                        path + "/browserUsers",
                        "Workload '" + workload.name() + "' asks for " + workload.browserUsers()
                                + " browser sessions, above the configured ceiling of "
                                + CAPABILITIES.maxConfiguredConcurrency() + ".",
                        "Use a smaller browser cohort. Browser sessions are budgeted separately "
                                + "from protocol virtual users."));
            }
        }

        return ValidationResult.of(findings);
    }

    @Override
    public EnginePlan compile(PerformanceTestSpecification specification, String workloadName) {
        // One workload per plan. A run compiles a plan for each of its workloads, so compiling all
        // the browser ones here would produce a plan claiming to be several tests at once.
        List<WorkloadSpec> workloads = browserWorkloads(specification).stream()
                .filter(workload -> workloadName == null || workloadName.equals(workload.name()))
                .toList();
        if (workloads.isEmpty()) {
            throw new IllegalArgumentException(
                    "This specification declares no browser workload named '" + workloadName
                            + "', so Playwright has nothing to compile");
        }

        Map<String, SourceSpec> sourcesByName = specification.spec().sources().stream()
                .filter(source -> source.name() != null)
                .collect(Collectors.toMap(SourceSpec::name, java.util.function.Function.identity(),
                        (left, right) -> left));

        CanonicalJson json = new CanonicalJson()
                .startObject()
                .field("planVersion", PLAN_VERSION)
                .field("engine", ENGINE_ID)
                .field("environment", environmentName(specification))
                .field("correlationHeader", correlationHeader(specification))
                .field("shardCount", DEFAULT_SHARD_COUNT)
                // This plan's own sessions, not every browser workload's. A plan whose capacity
                // described the whole document would have admission reserving the same sessions
                // once per plan.
                .field("peakBrowserSessions", peakSessions(workloads))
                .field("durationSeconds", durationSeconds(specification, workloads));

        json.startArray("workloads");
        for (WorkloadSpec workload : workloads) {
            writeWorkload(json, workload, sourcesByName.get(workload.sourceRef()));
        }
        json.endArray().endObject();

        return new EnginePlan(json.build(), DEFAULT_SHARD_COUNT,
                peakSessions(workloads), durationSeconds(specification, workloads));
    }

    /** Peak concurrent browser sessions across the workloads this plan covers. */
    private static int peakSessions(List<WorkloadSpec> workloads) {
        return workloads.stream().mapToInt(WorkloadSpec::peakUsers).sum();
    }

    @Override
    public RenderedWorkload render(PerformanceTestSpecification specification, ExecutionPlan plan,
            ShardAssignment assignment, TargetAddress target) {

        CanonicalJson json = new CanonicalJson()
                .startObject()
                .field("planDigest", plan.planDigest().value())
                .field("engine", ENGINE_ID)
                .field("runId", assignment.runId().toString())
                .field("shardIndex", assignment.shardIndex())
                .field("shardCount", assignment.shardCount())
                .field("targetBaseUrl", target.baseUrl())
                .field("correlationHeader", assignment.correlationHeader())
                .field("correlationValue", assignment.correlationValue())
                .field("compiledPlan", plan.planJson())
                .endObject();

        return new RenderedWorkload(
                "shard-%d-playwright-plan.json".formatted(assignment.shardIndex()),
                "application/json",
                json.build());
    }

    @Override
    public SampleSet parse(RunnerOutput output) {
        SampleSet combined = SampleSet.empty();
        for (RunnerOutput.ShardResult shard : output.shardResults()) {
            combined = combined.merge(PlaywrightResultParser.parse(shard.resultData()));
        }
        return combined;
    }

    static List<WorkloadSpec> browserWorkloads(PerformanceTestSpecification specification) {
        return specification.workloadsOfType(WorkloadType.BROWSER).stream()
                .filter(workload -> ENGINE_ID.equalsIgnoreCase(workload.engine()))
                .toList();
    }

    private static void writeWorkload(CanonicalJson json, WorkloadSpec workload, SourceSpec source) {
        var profile = workload.browserProfile() == null
                ? new PerformanceTestSpecification.BrowserProfile(null, null, null)
                : workload.browserProfile();

        /*
         * A workload whose source carries no material is refused here rather than compiled.
         *
         * Until now a git-pinned source produced a plan with sourceUri and sourceDigest empty and
         * nothing else — the git coordinates were validated, accepted, and then dropped. The runner
         * received a workload with no script to run, which is the silent-drop failure S9 removed for
         * engines arriving from a different direction. Refusing is right because compiling is a
         * claim: a plan is immutable, digested and executed, and one that describes no material
         * describes a test that cannot exist.
         */
        // A workspace:// URI counts as material here: it arrives in sourceUri like any other, and
        // the runner resolves it against its own checkout rather than fetching it.
        var git = source == null ? null : source.git();
        boolean hasArtifact = source != null && source.uri() != null && !source.uri().isBlank();
        if (!hasArtifact && git == null) {
            throw new IllegalArgumentException(
                    "Browser workload '" + workload.name() + "' references source '"
                            + workload.sourceRef() + "', which carries neither a stored artifact "
                            + "nor git coordinates, so there is no Playwright test to run");
        }

        json.startElement()
                .field("name", workload.name())
                .field("sourceRef", workload.sourceRef())
                .field("sourceUri", source == null ? "" : source.uri())
                .field("sourceDigest", source == null ? "" : source.digest())
                // Part of the plan, and therefore part of the plan digest: the commit a run
                // executed is recoverable from the stored version, which is the whole reason a
                // mutable ref is refused at validation.
                .field("sourceGitRepository", git == null ? "" : git.repository())
                .field("sourceGitRef", git == null ? "" : git.ref())
                .field("sourceGitPath", git == null ? "" : git.path())
                .field("sourceGitProject", git == null || git.project() == null ? "" : git.project())
                .field("browserUsers", workload.browserUsers() == null ? 0 : workload.browserUsers())
                .field("iterationsPerUser", iterations(workload))
                .field("device", profile.deviceOrDefault())
                .field("network", profile.networkOrDefault())
                .field("cpuThrottle", profile.cpuThrottleOrDefault())
                .field("startWhenWorkload", workload.startWhen() == null
                        ? "" : workload.startWhen().workload())
                .field("startWhenStage", workload.startWhen() == null
                        ? "" : workload.startWhen().stage())
                .endObject();
    }

    private static long durationSeconds(PerformanceTestSpecification specification,
            List<WorkloadSpec> workloads) {
        if (specification.spec().governance() != null
                && specification.spec().governance().maxDuration() != null) {
            return specification.spec().governance().maxDuration().toSeconds();
        }
        int maxSessions = workloads.stream().mapToInt(WorkloadSpec::peakUsers).max().orElse(1);
        int maxIterations = workloads.stream().mapToInt(PlaywrightEngineProvider::iterations)
                .max().orElse(1);
        return Duration.ofMinutes(Math.max(5, maxSessions * maxIterations)).toSeconds();
    }

    private static int iterations(WorkloadSpec workload) {
        return workload.iterationsPerUser() == null ? 1 : workload.iterationsPerUser();
    }

    private static String environmentName(PerformanceTestSpecification specification) {
        if (specification.spec() == null || specification.spec().environment() == null) {
            return "";
        }
        String name = specification.spec().environment().name();
        return name == null ? "" : name;
    }

    private static String correlationHeader(PerformanceTestSpecification specification) {
        if (specification.spec() == null || specification.spec().observability() == null) {
            return ShardAssignment.DEFAULT_CORRELATION_HEADER;
        }
        String header = specification.spec().observability().correlationHeader();
        return header == null || header.isBlank()
                ? ShardAssignment.DEFAULT_CORRELATION_HEADER : header;
    }
}
