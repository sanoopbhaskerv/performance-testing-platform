package com.perfplatform.domain.spec;

import com.perfplatform.domain.spec.support.SpecEnums;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
     * @param uri a stored artifact reference for document sources, including uploaded/pasted
     *            Playwright scripts
     * @param digest SHA-256 digest of uploaded/pasted Playwright source content; null for sources
     *               whose immutability is carried by another coordinate such as a git commit
     * @param git repository coordinates for a future Playwright source mode; null otherwise
     */
    public record SourceSpec(String name, SourceType type, String uri, String digest, GitSource git) {

        /** The shape that predates content-addressed Playwright script artifacts. */
        public SourceSpec(String name, SourceType type, String uri, GitSource git) {
            this(name, type, uri, null, git);
        }

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
    public record AuthenticationSpec(String name, String type, String injectAs, String secretRef) {

        /** The shape that predates runtime injection targets. */
        public AuthenticationSpec(String name, String type, String secretRef) {
            this(name, type, null, secretRef);
        }
    }

    /**
     * Data a workload draws on, so that every virtual user does not send the same request.
     *
     * <p>A scenario binds to a column by name — {@code path: /orders/${orderId}} — and each
     * iteration takes the next row.
     *
     * @param columns          the column names this test expects the dataset to have. Declared here
     *                         because validation is side-effect-free and has to work before any file
     *                         is attached: it is what a scenario's {@code ${...}} references are
     *                         checked against. The file's actual header is compared to this before a
     *                         run, so a re-exported dataset cannot silently bind different values to
     *                         the same names. Empty when the author has not said, in which case
     *                         references can only be reported as unverifiable
     * @param partitionByShard when true, each shard receives a non-overlapping slice, so sharding
     *                         a run does not make every generator replay the same rows
     */
    public record DatasetSpec(
            String name,
            DataSource source,
            List<String> columns,
            boolean partitionByShard) {

        public DatasetSpec {
            columns = immutable(columns);
        }

        /** A dataset declared without columns, the form that predates column binding. */
        public DatasetSpec(String name, DataSource source, boolean partitionByShard) {
            this(name, source, List.of(), partitionByShard);
        }

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
            String authRef,
            LoadModel model,
            List<ScenarioSpec> scenarios,
            StartCondition startWhen,
            Integer browserUsers,
            Integer iterationsPerUser,
            BrowserProfile browserProfile) {

        public WorkloadSpec {
            scenarios = immutable(scenarios);
        }

        /**
         * Peak concurrent users this workload asks for, used by admission control.
         *
         * <p>For an open model this is the declared {@code maxConcurrency}, <b>not</b> the arrival
         * rate. The two are different quantities and only one of them describes how much of a
         * generator the test occupies: 500 requests a second against a 20 ms endpoint is about ten
         * concurrent requests, and against a 2 s endpoint it is a thousand. Budgeting against the
         * rate would make a heavy test look free and a light one look enormous, and admission would
         * be protecting the wrong number.
         *
         * <p>A model with no ceiling reports zero rather than guessing one. The validator refuses
         * such a specification outright, so this is only reached while validating a document that
         * is already going to be rejected.
         */
        public int peakUsers() {
            if (type == WorkloadType.BROWSER) {
                return browserUsers == null ? 0 : browserUsers;
            }
            if (model == null || model.stages().isEmpty()) {
                return 0;
            }
            if (model.isArrivalRate()) {
                return model.maxConcurrency() == null ? 0 : model.maxConcurrency();
            }
            return model.stages().stream().mapToInt(LoadStage::targetUsers).max().orElse(0);
        }

        /**
         * Peak requests per second this workload asks for, or zero in a closed model.
         *
         * <p>Reported alongside the concurrency ceiling rather than instead of it: a rate is what a
         * consumer asked for and what the report should quote back, and the ceiling is what the
         * platform reserved.
         */
        public int peakArrivalRate() {
            if (model == null || !model.isArrivalRate()) {
                return 0;
            }
            return model.stages().stream()
                    .map(LoadStage::targetRate)
                    .filter(java.util.Objects::nonNull)
                    .mapToInt(Integer::intValue)
                    .max()
                    .orElse(0);
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

        /** The shape that predates workload-level authentication. */
        public WorkloadSpec(String name, WorkloadType type, String engine, String sourceRef,
                String authRef,
                LoadModel model, List<ScenarioSpec> scenarios, StartCondition startWhen,
                Integer browserUsers, Integer iterationsPerUser) {
            this(name, type, engine, sourceRef, authRef, model, scenarios, startWhen,
                    browserUsers, iterationsPerUser, null);
        }

        /** The shape that predates workload-level authentication and browser profiles. */
        public WorkloadSpec(String name, WorkloadType type, String engine, String sourceRef,
                LoadModel model, List<ScenarioSpec> scenarios, StartCondition startWhen,
                Integer browserUsers, Integer iterationsPerUser) {
            this(name, type, engine, sourceRef, null, model, scenarios, startWhen,
                    browserUsers, iterationsPerUser, null);
        }
    }

    /**
     * Browser measurement conditions.
     *
     * <p>A web vital has no meaning without its device, network and CPU profile. A run on a
     * throttled mobile profile and a run on an unthrottled desktop profile must not be compared as
     * if they measured the same experience.
     *
     * @param device mobile, desktop or a named Playwright device
     * @param network slow-4g, fast-4g, wifi or none
     * @param cpuThrottle CPU throttle multiplier; null means the engine default
     */
    public record BrowserProfile(String device, String network, Integer cpuThrottle) {

        public String deviceOrDefault() {
            return device == null || device.isBlank() ? "mobile" : device.trim();
        }

        public String networkOrDefault() {
            return network == null || network.isBlank() ? "slow-4g" : network.trim();
        }

        public int cpuThrottleOrDefault() {
            return cpuThrottle == null ? 4 : cpuThrottle;
        }
    }

    /**
     * How load is shaped over time.
     *
     * @param type    closed ({@code stages}) or open ({@code arrival-rate}); see {@link LoadModelType}
     * @param stages  the shape, in order. Stage order is meaningful — reordering describes a
     *                different test
     * @param maxConcurrency the ceiling on requests in flight at once. <b>Required for
     *                {@code arrival-rate} and refused for {@code stages}</b>, where the user count
     *                <em>is</em> the concurrency. In an open model this is the only thing standing
     *                between a stalled target and a generator that accumulates in-flight requests
     *                until it dies and reports its own failure as the target's — and it is what
     *                admission control budgets against, because a rate says nothing about how much
     *                of a generator a test will occupy
     */
    public record LoadModel(LoadModelType type, List<LoadStage> stages, Integer maxConcurrency) {

        public LoadModel {
            stages = immutable(stages);
        }

        /** The shape that predates the open model. */
        public LoadModel(LoadModelType type, List<LoadStage> stages) {
            this(type, stages, null);
        }

        public boolean isArrivalRate() {
            return type != null && type.isOpen();
        }
    }

    /**
     * One stage of a load model: hold or move toward a target over {@code duration}.
     *
     * <p>Which target depends on the model. A closed model reads {@code targetUsers}; an open one
     * reads {@code targetRate}. A final stage targeting zero expresses ramp-down in either.
     *
     * <p>Both fields exist on one record rather than in two stage types because a stage is a
     * point on a shape, and the shape's arithmetic — durations, ordering, ramp-down — is identical
     * whichever quantity is being ramped. The validator refuses a stage that sets the field its
     * model does not read, so a document cannot quietly mean something other than it says.
     *
     * @param name        optional stage name. Browser workloads can wait for this boundary.
     * @param targetUsers concurrent users, for a closed model
     * @param targetRate  requests per second, for an open model. Null in a closed model
     */
    public record LoadStage(String name, Duration duration, int targetUsers, Integer targetRate) {

        public LoadStage {
            name = name == null || name.isBlank() ? null : name.trim();
        }

        public LoadStage(Duration duration, int targetUsers, Integer targetRate) {
            this(null, duration, targetUsers, targetRate);
        }

        /** The shape that predates the open model. */
        public LoadStage(Duration duration, int targetUsers) {
            this(null, duration, targetUsers, null);
        }

        /** What this stage asks for, in whichever unit its model reads. Zero means ramp-down. */
        public int target(LoadModelType type) {
            if (type != null && type.isOpen()) {
                return targetRate == null ? 0 : targetRate;
            }
            return targetUsers;
        }
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
     * @param headers     request headers. <b>Never credentials</b> — {@code Authorization} and
     *                    {@code Cookie} are refused outright, and any value shaped like a token is
     *                    rejected. Authentication belongs in {@code spec.authentication} as a
     *                    reference the runner resolves at execution time
     * @param query       query parameters, appended to {@code path}. Also credential-checked: an API
     *                    key in a query string ends up in every access log between here and the
     *                    target
     * @param body        the request body, for methods that carry one
     */
    public record ScenarioSpec(
            String name,
            int weight,
            String operationId,
            String method,
            String path,
            Map<String, String> headers,
            Map<String, String> query,
            RequestBody body,
            List<StepSpec> steps) {

        /**
         * A request body.
         *
         * <p>Inline content only for now. A body drawn from a dataset — one row per iteration, so a
         * thousand users do not all submit the same order — needs the dataset partitioning that is
         * still ahead, and is deliberately not faked with a placeholder here.
         *
         * @param contentType the media type sent, e.g. {@code application/json}
         * @param content     the body itself
         */
        public record RequestBody(String contentType, String content) {

            /** The media type to send, defaulting to JSON, which is what an API test almost always is. */
            public String contentTypeOrDefault() {
                return contentType == null || contentType.isBlank()
                        ? "application/json" : contentType.trim();
            }

            public boolean isEmpty() {
                return content == null || content.isBlank();
            }
        }

        public ScenarioSpec {
            headers = immutableMap(headers);
            query = immutableMap(query);
            steps = immutable(steps);
        }

        /** A scenario stating one request directly, with no steps. The form that predates journeys. */
        public ScenarioSpec(String name, int weight, String operationId, String method, String path,
                Map<String, String> headers, Map<String, String> query, RequestBody body) {
            this(name, weight, operationId, method, path, headers, query, body, List.of());
        }

        /**
         * Whether this scenario is a sequence rather than a single request.
         *
         * <p>The two forms are exclusive in practice: a scenario either states one request through
         * {@code method}/{@code path} or lists {@code steps}. Both being present is refused at
         * validation rather than resolved by a precedence rule, because a rule would silently ignore
         * whichever half the author actually meant.
         */
        public boolean isJourney() {
            return !steps.isEmpty();
        }

        /**
         * This scenario as an ordered list of steps, whichever form it was written in.
         *
         * <p>What every consumer downstream of validation actually wants: a renderer, an analyzer and
         * a dataset binder all treat a single request as a journey of one, and asking each of them to
         * branch on the form is how the two paths drift apart.
         */
        public List<StepSpec> asSteps() {
            return isJourney()
                    ? steps
                    : List.of(new StepSpec(name, method, path, headers, query, body,
                            List.of(), List.of(), null));
        }

        /**
         * A scenario that refers to an operation in an imported source.
         *
         * <p>The other form — the request stated directly — uses the canonical constructor. Both are
         * first-class; this one exists because it is the shorter of the two and predates the manual
         * form.
         */
        public ScenarioSpec(String name, int weight, String operationId) {
            this(name, weight, operationId, null, null, Map.of(), Map.of(), null);
        }

        /** A scenario stating a bare method and path, with no headers, query or body. */
        public ScenarioSpec(String name, int weight, String operationId, String method, String path) {
            this(name, weight, operationId, method, path, Map.of(), Map.of(), null);
        }

        /** Whether this scenario states its own request rather than referring to an imported one. */
        public boolean hasExplicitRequest() {
            return path != null && !path.isBlank();
        }

        /** The method to use, defaulting to {@code GET} when the scenario does not state one. */
        public String methodOrDefault() {
            return method == null || method.isBlank() ? "GET" : method.trim().toUpperCase(Locale.ROOT);
        }

        /** Whether this scenario sends a body. */
        public boolean hasBody() {
            return body != null && !body.isEmpty();
        }
    }

    /**
     * One request within a journey.
     *
     * <p>A {@link ScenarioSpec} used to be a single request, which meant no test could sign in: there
     * was nowhere to put the second call, and no way to carry a session from the first to it. A
     * scenario is now an ordered list of these.
     *
     * <p>Shares the request fields with {@code ScenarioSpec} rather than reusing it, because a step
     * has no weight and no operationId — traffic is apportioned between journeys, not between the
     * requests inside one — and a record carrying fields that are meaningless for half its uses
     * teaches the wrong model to everyone who reads it.
     *
     * @param name      identifies this step in results and findings, e.g. {@code sign-in}
     * @param extract   values pulled out of the response for later steps to use
     * @param expect    what must be true of the response, checked at run time
     * @param thinkTime how long a user pauses after this step before the next. Null means no pause,
     *                  which is realistic only for a machine
     */
    public record StepSpec(
            String name,
            String method,
            String path,
            Map<String, String> headers,
            Map<String, String> query,
            ScenarioSpec.RequestBody body,
            List<ExtractionSpec> extract,
            List<AssertionSpec> expect,
            Duration thinkTime) {

        public StepSpec {
            headers = immutableMap(headers);
            query = immutableMap(query);
            extract = immutable(extract);
            expect = immutable(expect);
        }

        /** A step stating a bare method and path. */
        public StepSpec(String name, String method, String path) {
            this(name, method, path, Map.of(), Map.of(), null, List.of(), List.of(), null);
        }

        /** The method to use, defaulting to {@code GET} when the step does not state one. */
        public String methodOrDefault() {
            return method == null || method.isBlank() ? "GET" : method.trim().toUpperCase(Locale.ROOT);
        }

        /** Whether this step sends a body. */
        public boolean hasBody() {
            return body != null && !body.isEmpty();
        }

        /**
         * The {@code ${name}} variables this step actually sends.
         *
         * <p>Scans everything a step can carry a variable in — path, query, headers and body. It is
         * what distinguishes an extraction that feeds the journey from one that resolves and is then
         * discarded, and the second kind is a real defect rather than a tidiness problem: the step
         * that was supposed to consume it is still sending whatever literal the capture recorded, so
         * the journey looks correlated, passes, and hard-codes a value that will be stale by the time
         * anyone runs it at load.
         *
         * @return the variable names referenced, in the order first seen
         */
        public Set<String> referencedVariables() {
            Set<String> found = new LinkedHashSet<>();
            collectVariables(path, found);
            query.values().forEach(value -> collectVariables(value, found));
            headers.values().forEach(value -> collectVariables(value, found));
            if (body != null) {
                collectVariables(body.content(), found);
            }
            return Collections.unmodifiableSet(found);
        }
    }

    private static final Pattern VARIABLE_REFERENCE = Pattern.compile("\\$\\{([^}]+)}");

    private static void collectVariables(String text, Set<String> into) {
        if (text == null || text.isEmpty()) {
            return;
        }
        Matcher matcher = VARIABLE_REFERENCE.matcher(text);
        while (matcher.find()) {
            into.add(matcher.group(1));
        }
    }

    /**
     * A value taken out of one response so a later step can use it.
     *
     * <p>This is correlation, and it is what makes an authenticated journey possible: sign in, take
     * the session out of the response, send it with everything after. The extracted value enters the
     * same {@code ${name}} namespace as a dataset column, so a step refers to it exactly as it refers
     * to test data and nothing downstream has to know which it was.
     *
     * <h2>Why a failed extraction cannot be tolerated</h2>
     * When a pattern does not match, the engine substitutes its default and sends the request
     * anyway. The target answers 4xx in a couple of milliseconds, and the run reports its best
     * latency ever for a system that did no work. That is the failure mode this platform guards
     * against in five other places, arriving from a new direction — so the renderer emits a sentinel
     * default and an assertion that fails the sample when the sentinel survives. A broken
     * correlation shows up as an error rate, never as good latency.
     *
     * @param name       the {@code ${name}} later steps will use
     * @param from       where in the response to look
     * @param expression a JSON path, a regular expression with one capturing group, a header name,
     *                   or a cookie name — whichever {@code from} calls for
     * @param required   whether the journey can continue without it. False is rare and deliberate:
     *                   an optional value still produces its sentinel, but no assertion fails on it
     */
    public record ExtractionSpec(String name, ExtractionSource from, String expression, boolean required) {

        /** A required extraction, which is almost always what is meant. */
        public ExtractionSpec(String name, ExtractionSource from, String expression) {
            this(name, from, expression, true);
        }
    }

    /** Where in a response an {@link ExtractionSpec} looks. */
    public enum ExtractionSource {
        /** A JSON path into the response body, e.g. {@code $.token}. */
        JSON_BODY,
        /** A regular expression over the response body, with exactly one capturing group. */
        REGEX_BODY,
        /** A response header, by name. */
        HEADER,
        /** A cookie from {@code Set-Cookie}, by name. */
        SET_COOKIE,
        /** The response status code. Takes no expression. */
        STATUS;

        public static ExtractionSource from(String raw) {
            return SpecEnums.parse(ExtractionSource.class, raw, "extraction source");
        }
    }

    /**
     * What must be true of a response.
     *
     * <p>On by default in the studio, as it is in every comparable tool, and for a blunt reason: a
     * load test of a broken deployment reports beautiful numbers for a wall of 500s. Latency without
     * a correctness check is a measurement of how fast a system can refuse to work.
     *
     * @param subject what to look at
     * @param value   the status code, the text, or the pattern — whichever {@code operator} calls for
     */
    public record AssertionSpec(AssertionSubject subject, AssertionOperator operator, String value) {

        /** The commonest assertion: the response status is what the recording saw. */
        public static AssertionSpec status(int code) {
            return new AssertionSpec(AssertionSubject.STATUS, AssertionOperator.EQUALS,
                    String.valueOf(code));
        }
    }

    /** What an {@link AssertionSpec} looks at. */
    public enum AssertionSubject {
        STATUS,
        BODY;

        public static AssertionSubject from(String raw) {
            return SpecEnums.parse(AssertionSubject.class, raw, "assertion subject");
        }
    }

    /** How an {@link AssertionSpec} compares. */
    public enum AssertionOperator {
        EQUALS,
        CONTAINS,
        /** A regular expression the subject must match. */
        MATCHES;

        public static AssertionOperator from(String raw) {
            return SpecEnums.parse(AssertionOperator.class, raw, "assertion operator");
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

    /**
     * An immutable copy that keeps its authoring order.
     *
     * <p>{@code Map.copyOf} returns an unordered map whose iteration order is randomized per JVM
     * run, which would make a rendered plan's header order differ between two runs of the same
     * immutable version. The digest is taken over a sorted form regardless, so ordering here is
     * about the plan reading the way its author wrote it.
     */
    private static Map<String, String> immutableMap(Map<String, String> values) {
        return values == null || values.isEmpty()
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(values));
    }
}
