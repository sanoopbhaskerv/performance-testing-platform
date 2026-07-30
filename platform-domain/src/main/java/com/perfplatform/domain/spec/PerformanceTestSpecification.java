package com.perfplatform.domain.spec;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The canonical, engine-neutral description of a performance test.
 *
 * <p>This is the single model behind all three consumer channels. The web UI, the REST API and
 * YAML/GitOps all produce exactly this — they do not maintain separate request structures. That
 * is the whole point: V1 accepted only {@code team} and {@code type} over HTTP while YAML carried
 * a richer shape, so the two channels could not express the same test.
 *
 * <p>Instances are immutable. A {@code TestDefinitionVersion} stores one specification and its
 * SHA-256 digest, and cannot be modified after creation, so a run that references a version is
 * exactly reproducible.
 *
 * <p>Nothing here is engine-specific. A workload declares what it needs; which engine satisfies
 * it is resolved through the engine SPI.
 *
 * @param apiVersion the schema version, e.g. {@code performance.ameriprise.com/v1alpha1}
 * @param kind       always {@code PerformanceTest} for this schema
 * @param metadata   identity and ownership
 * @param spec       the test itself
 * @see <a href="../../../../../../../docs/architecture/performance-testing-platform-target-architecture.md">Target architecture §7</a>
 */
public record PerformanceTestSpecification(
        String apiVersion,
        String kind,
        SpecMetadata metadata,
        TestSpec spec) {

    /** The only API version this schema release understands. */
    public static final String SUPPORTED_API_VERSION = "performance.ameriprise.com/v1alpha1";

    /** The only kind this schema release understands. */
    public static final String SUPPORTED_KIND = "PerformanceTest";

    public PerformanceTestSpecification {
        apiVersion = apiVersion == null ? null : apiVersion.trim();
        kind = kind == null ? null : kind.trim();
    }

    /**
     * Identity and ownership.
     *
     * @param name        unique within the owning team
     * @param team        the owning team; every authorization decision starts here
     * @param application the application under test
     * @param labels      free-form metadata for search and reporting, never for policy
     */
    public record SpecMetadata(
            String name,
            String team,
            String application,
            Map<String, String> labels) {

        public SpecMetadata {
            labels = labels == null ? Map.of() : Map.copyOf(labels);
        }
    }

    /**
     * The body of the test.
     *
     * @param environment   where it runs and what class of target it hits
     * @param sources       imported material the workloads draw on
     * @param authentication credential <em>references</em> — never credential values
     * @param datasets      test data, optionally partitioned across shards
     * @param workloads     one or more protocol or browser workloads
     * @param objectives    the pass/fail contract
     * @param observability correlation and telemetry settings
     * @param governance    duration ceiling, approval policy and retention
     */
    public record TestSpec(
            EnvironmentSpec environment,
            List<SourceSpec> sources,
            List<AuthenticationSpec> authentication,
            List<DatasetSpec> datasets,
            List<WorkloadSpec> workloads,
            List<ObjectiveSpec> objectives,
            ObservabilitySpec observability,
            GovernanceSpec governance) {

        public TestSpec {
            sources = immutable(sources);
            authentication = immutable(authentication);
            datasets = immutable(datasets);
            workloads = immutable(workloads);
            objectives = immutable(objectives);
        }
    }

    /**
     * @param name        the environment name, e.g. {@code performance}
     * @param runnerCell  which execution cell runs the workload; determines network reachability
     * @param targetClass production or non-production, which drives approval policy
     */
    public record EnvironmentSpec(String name, String runnerCell, TargetClass targetClass) {
    }

    /**
     * Imported material a workload compiles from.
     *
     * @param uri a stored artifact reference for document sources; null for git-backed sources
     * @param git repository coordinates for a Playwright source; null otherwise
     */
    public record SourceSpec(String name, SourceType type, String uri, GitSource git) {

        /**
         * @param ref an immutable commit SHA. A moving branch is rejected for an approved
         *            definition, because the code that ran would not be recoverable from the
         *            stored version.
         */
        public record GitSource(String repository, String ref, String path, String project) {
        }
    }

    /**
     * A credential reference.
     *
     * <p>{@code secretRef} holds a pointer such as {@code vault://performance/proposal/client}.
     * It must never hold the secret itself; the value is resolved at execution time and mounted
     * only into the runner. Inline values are rejected during validation.
     */
    public record AuthenticationSpec(String name, String type, String secretRef) {
    }

    /**
     * @param partitionByShard when true, each shard receives a non-overlapping slice, so sharding
     *                         a run does not make every generator replay the same rows
     */
    public record DatasetSpec(String name, DataSource source, boolean partitionByShard) {

        public record DataSource(String type, String uri) {
        }
    }

    /**
     * One workload within the test.
     *
     * <p>Protocol and browser workloads share this shape but use different fields: a protocol
     * workload carries a {@link LoadModel} and weighted {@link ScenarioSpec}s, while a browser
     * workload carries {@code browserUsers} and {@code iterationsPerUser} and may declare a
     * {@link StartCondition} so it runs during another workload's steady state.
     *
     * @param engine requested engine, e.g. {@code jmeter}; validated against engine capabilities
     */
    public record WorkloadSpec(
            String name,
            WorkloadType type,
            String engine,
            String sourceRef,
            LoadModel model,
            List<ScenarioSpec> scenarios,
            StartCondition startWhen,
            Integer browserUsers,
            Integer iterationsPerUser) {

        public WorkloadSpec {
            scenarios = immutable(scenarios);
        }

        /** Peak concurrent users this workload asks for, used by admission control. */
        public int peakUsers() {
            if (type == WorkloadType.BROWSER) {
                return browserUsers == null ? 0 : browserUsers;
            }
            if (model == null || model.stages().isEmpty()) {
                return 0;
            }
            return model.stages().stream().mapToInt(LoadStage::targetUsers).max().orElse(0);
        }

        /**
         * Total wall-clock duration of the staged model, or zero for a browser workload.
         *
         * <p>Stages with no duration are skipped rather than propagated. This is called during
         * validation of a specification that may well be malformed — a stage missing its duration
         * is reported as its own finding, and this must not throw before that finding is
         * returned, since it would surface as a 500 from the validate endpoint instead.
         */
        public Duration totalDuration() {
            if (model == null || model.stages().isEmpty()) {
                return Duration.ZERO;
            }
            return model.stages().stream()
                    .map(LoadStage::duration)
                    .filter(java.util.Objects::nonNull)
                    .reduce(Duration.ZERO, Duration::plus);
        }
    }

    /** How load is shaped over time. */
    public record LoadModel(LoadModelType type, List<LoadStage> stages) {

        public LoadModel {
            stages = immutable(stages);
        }
    }

    /**
     * One stage of a staged load model: hold or ramp toward {@code targetUsers} over
     * {@code duration}. A final stage targeting zero users expresses ramp-down.
     */
    public record LoadStage(Duration duration, int targetUsers) {
    }

    /**
     * A weighted journey within a workload.
     *
     * <p>Weights are what V1 discarded when it flattened every scenario into one endpoint list.
     * Preserving them is what allows a realistic traffic mix rather than an even spread across
     * every endpoint.
     *
     * <h2>How a scenario says what to call</h2>
     * Two ways, and the platform prefers the first that is present:
     * <ol>
     *   <li><b>{@code method} and {@code path} directly.</b> The manual form: the consumer states
     *       the request. This is the simplest case CLAUDE.md §23 lists under manual entry, and it is
     *       what makes a specification able to address a real endpoint today.</li>
     *   <li><b>{@code operationId} alone.</b> A reference into an imported source — OpenAPI, Postman,
     *       HAR. No importer exists until Increment 2, so until then an operation identifier resolves
     *       to nothing and the engine addresses {@code /{operationId}}: deliberately obvious rather
     *       than plausible, so nobody mistakes an unresolved reference for a real request.</li>
     * </ol>
     * Both are optional so that a specification written against a future importer stays valid, and
     * so that adding these fields does not invalidate any document written before them.
     *
     * @param weight      relative share of iterations; weights across a workload should sum to 100
     * @param operationId reference into an imported source, resolved from Increment 2 onward
     * @param method      HTTP method, e.g. {@code GET}. Null when the scenario defers to its source
     * @param path        request path beginning with {@code /}, relative to the resolved target's
     *                    base URL. Never a full URL: a specification cannot choose its own host, and
     *                    accepting one here would be a way around that (CLAUDE.md §20)
     */
    public record ScenarioSpec(
            String name,
            int weight,
            String operationId,
            String method,
            String path) {

        /**
         * A scenario that refers to an operation in an imported source.
         *
         * <p>The other form — {@code method} and {@code path} stated directly — uses the canonical
         * constructor. Both are first-class; this one exists because it is the shorter of the two and
         * predates the manual form.
         */
        public ScenarioSpec(String name, int weight, String operationId) {
            this(name, weight, operationId, null, null);
        }

        /** Whether this scenario states its own request rather than referring to an imported one. */
        public boolean hasExplicitRequest() {
            return path != null && !path.isBlank();
        }

        /** The method to use, defaulting to {@code GET} when the scenario does not state one. */
        public String methodOrDefault() {
            return method == null || method.isBlank() ? "GET" : method.trim().toUpperCase(Locale.ROOT);
        }
    }

    /** Starts a workload when another workload reaches a named stage. */
    public record StartCondition(String workload, String stage) {
    }

    /**
     * One objective in the pass/fail contract.
     *
     * @param metric     the normalized metric name, e.g. {@code http.response.time}
     * @param scope      the workload the objective applies to; null means the whole test
     * @param percentile the percentile to evaluate, or null for a non-percentile metric
     */
    public record ObjectiveSpec(String metric, String scope, Integer percentile, Threshold threshold) {
    }

    /**
     * @param correlationHeader the header injected into workload traffic so application telemetry
     *                          can be correlated to this run
     * @param collect           which telemetry series to attach to the report
     */
    public record ObservabilitySpec(
            String provider,
            String correlationHeader,
            String serviceTagSelector,
            List<String> collect) {

        public ObservabilitySpec {
            collect = immutable(collect);
        }
    }

    /**
     * @param maxDuration    ceiling enforced server-side, independent of what the workloads declare
     * @param approvalPolicy named policy deciding whether a run needs approval before executing
     * @param retention      how long artifacts are kept
     */
    public record GovernanceSpec(Duration maxDuration, String approvalPolicy, Duration retention) {
    }

    // ── Convenience accessors ────────────────────────────────────────────

    /** The owning team, or empty if metadata is absent. Never throws on a malformed spec. */
    public Optional<String> teamName() {
        return Optional.ofNullable(metadata).map(SpecMetadata::team);
    }

    /** Workloads of the given type, in declaration order. */
    public List<WorkloadSpec> workloadsOfType(WorkloadType type) {
        if (spec == null) {
            return List.of();
        }
        return spec.workloads().stream().filter(workload -> workload.type() == type).toList();
    }

    /**
     * Peak protocol virtual users across all protocol workloads. Browser sessions are excluded
     * deliberately: they are budgeted against a separate, much smaller ceiling.
     */
    public int peakProtocolUsers() {
        return workloadsOfType(WorkloadType.PROTOCOL).stream()
                .mapToInt(WorkloadSpec::peakUsers)
                .sum();
    }

    /** Peak concurrent browser sessions across all browser workloads. */
    public int peakBrowserSessions() {
        return workloadsOfType(WorkloadType.BROWSER).stream()
                .mapToInt(WorkloadSpec::peakUsers)
                .sum();
    }

    private static <T> List<T> immutable(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
