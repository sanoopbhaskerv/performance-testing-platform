package com.perfplatform.engine.jmeter;

import com.perfplatform.domain.plan.ExecutionPlan;
import com.perfplatform.domain.execution.RuntimeCredential;
import com.perfplatform.domain.spec.PerformanceTestSpecification;
import com.perfplatform.domain.spec.PerformanceTestSpecification.AuthenticationSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.DatasetSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.LoadStage;
import com.perfplatform.domain.spec.PerformanceTestSpecification.ScenarioSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.AssertionSubject;
import com.perfplatform.domain.spec.PerformanceTestSpecification.AssertionSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.ExtractionSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.StepSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.WorkloadSpec;
import com.perfplatform.domain.target.TargetAddress;
import com.perfplatform.engine.spi.RenderedWorkload;
import com.perfplatform.engine.spi.ShardAssignment;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Renders the JMX one shard executes.
 *
 * <h2>Scenario weighting</h2>
 * Each scenario becomes a {@code ThroughputController} in percent mode, carrying its weight. This is
 * what replaces V1's flattening (defect D4): scenario boundaries survive into the generated plan,
 * their names appear in the results so per-scenario objectives can be evaluated, and the traffic mix
 * is the one the consumer asked for rather than an even spread across every endpoint.
 *
 * <h2>Load shape</h2>
 * Both shapes are rendered through the Custom Thread Groups plugin, which the runner image installs
 * and which a local development JMeter must have too.
 *
 * <ul>
 *   <li><b>A closed model</b> becomes one Ultimate Thread Group whose layers sum to the requested
 *       concurrency curve ({@link ConcurrencyProfile}), so concurrency <em>glides</em> through the
 *       profile. It previously became one vanilla thread group per stage, each starting from zero,
 *       which stepped at every boundary — a different test, because pools, autoscalers and circuit
 *       breakers respond to the transition and not only to the level.</li>
 *   <li><b>An open model</b> becomes one Free-Form Arrivals Thread Group whose schedule rows map
 *       one-to-one onto the specification's stages, with {@code maxConcurrency} as its concurrency
 *       limit. Vanilla JMeter cannot express an open model at all.</li>
 * </ul>
 *
 * <p>Both were verified against a real JMeter with the plugin installed rather than reasoned about
 * from documentation: the observed arrival rate and the observed active-thread curve follow what the
 * specification asked for.
 *
 * <h2>How a scenario becomes a request</h2>
 * A scenario that states its own {@code method} and {@code path} is rendered as written — the manual
 * form, and what lets a specification address a real endpoint today. Only the path comes from the
 * document; scheme, host and port come from the target the platform resolved, so a specification
 * still cannot choose where its traffic goes.
 *
 * <h2>Data</h2>
 * A declared dataset becomes a plan-level {@code CSVDataSet}, and a scenario binds to a column with
 * {@code ${name}} anywhere in its path, query, headers or body. Each shard reads its own
 * non-overlapping partition, written beside the plan under {@link #partitionFileName}.
 *
 * <h2>What this cannot do yet</h2>
 * A scenario that names only an {@code operationId} refers to an operation in an imported source
 * without stating the request itself. Such samplers address {@code /{operationId}}, which exercises
 * the whole pipeline but does not reproduce a real request — deliberately obvious rather than
 * plausible. The importers produce scenarios that state their request directly, so this affects only
 * hand-written documents.
 */
final class JmeterWorkloadRenderer {

    /** Percent-execution mode for {@code ThroughputController}. */
    private static final int PERCENT_EXECUTION = 1;

    private static final String SESSION_DATASET_FILE_NAME = "sessions.csv";
    private static final String SESSION_VALUE_VARIABLE = "session_value";

    private JmeterWorkloadRenderer() {
    }

    /**
     * @param target the base URL this shard sends traffic to, resolved server-side. Never taken
     *               from the specification: a document that could name its own target would route
     *               around the allowlist it was validated against
     */
    static RenderedWorkload render(PerformanceTestSpecification specification, ExecutionPlan plan,
            ShardAssignment assignment, TargetAddress target) {

        // Only the plan's own workload. A run compiles a plan per workload, so rendering every
        // protocol workload here would make each shard execute all of them — multiplying the load
        // by the number of workloads while every plan still claimed to describe one.
        List<WorkloadSpec> workloads = JmeterPlanCompiler.protocolWorkloads(specification).stream()
                .filter(workload -> plan.workloadName().equals(workload.name()))
                .toList();
        if (workloads.isEmpty()) {
            throw new IllegalArgumentException(
                    "This specification has no JMeter protocol workload named '"
                            + plan.workloadName() + "' to render");
        }
        Map<String, AuthenticationSpec> authByName = authenticationsByName(specification);

        JmxWriter jmx = new JmxWriter();
        jmx.element("TestPlan", attributes("TestPlanGui", "TestPlan",
                        planName(specification, assignment)))
                .boolProp("TestPlan.functional_mode", false)
                .boolProp("TestPlan.tearDown_on_shutdown", true)
                .boolProp("TestPlan.serialize_threadgroups", false)
                .raw("""
                        <elementProp name="TestPlan.arguments" elementType="Arguments" \
                        guiclass="ArgumentsPanel" testclass="Arguments">
                        <collectionProp name="Arguments.arguments"/>
                        </elementProp>
                        """)
                .endWithChildren();

        // Correlation and connection defaults are declared once at plan level so every sampler in
        // every thread group inherits them. A header attached per sampler would be one edit away
        // from a request that carries no run identifier and cannot be attributed.
        writeHeaderManager(jmx, assignment);
        writeHttpDefaults(jmx, target);
        writeDatasets(jmx, specification);
        writeSessionDataset(jmx, specification);

        for (WorkloadSpec workload : workloads) {
            writeWorkload(jmx, workload, assignment, target, authByName.get(workload.authRef()));
        }

        jmx.endChildren();

        return new RenderedWorkload(
                "shard-%d.jmx".formatted(assignment.shardIndex()),
                "application/xml",
                jmx.build());
    }

    private static void writeWorkload(JmxWriter jmx, WorkloadSpec workload,
            ShardAssignment assignment, TargetAddress target, AuthenticationSpec authentication) {

        List<LoadStage> stages = JmeterPlanCompiler.stages(workload);
        if (stages.isEmpty()) {
            return;
        }

        if (workload.model() != null && workload.model().isArrivalRate()) {
            writeArrivalsThreadGroup(jmx, workload, stages, assignment, target, authentication);
        } else {
            writeConcurrencyThreadGroup(jmx, workload, stages, assignment, target, authentication);
        }
    }

    /**
     * A closed model: concurrency gliding through the profile's shape.
     *
     * <p>One Ultimate Thread Group whose layers sum to the requested curve
     * ({@link ConcurrencyProfile}). This replaces a thread group per stage, which stepped at every
     * boundary — the previous stage's threads stopped and the next stage's started — and therefore
     * measured something other than the ramp the consumer described.
     */
    private static void writeConcurrencyThreadGroup(JmxWriter jmx, WorkloadSpec workload,
            List<LoadStage> stages, ShardAssignment assignment, TargetAddress target,
            AuthenticationSpec authentication) {

        List<ConcurrencyProfile.Layer> layers = ConcurrencyProfile.layersFor(stages);
        if (layers.isEmpty()) {
            return;
        }

        jmx.element("kg.apc.jmeter.threads.UltimateThreadGroup",
                        attributes("kg.apc.jmeter.threads.UltimateThreadGroupGui",
                                "kg.apc.jmeter.threads.UltimateThreadGroup", workload.name()))
                .raw("<collectionProp name=\"ultimatethreadgroupdata\">");

        int row = 0;
        for (ConcurrencyProfile.Layer layer : layers) {
            long threads = threadsForShard(layer.threads(), assignment);
            // A shard whose slice of a layer rounds to nothing emits no row. A row of zero threads
            // is not a smaller layer, it is a group JMeter starts and immediately ends.
            if (threads <= 0) {
                continue;
            }
            jmx.raw("""
                    <collectionProp name="layer-%d">
                    <stringProp name="1">%d</stringProp>
                    <stringProp name="2">%d</stringProp>
                    <stringProp name="3">%d</stringProp>
                    <stringProp name="4">%d</stringProp>
                    <stringProp name="5">%d</stringProp>
                    </collectionProp>"""
                    .formatted(row++, threads, layer.delaySeconds(), layer.rampSeconds(),
                            layer.holdSeconds(), layer.shutdownSeconds()));
        }

        jmx.raw("</collectionProp>")
                .raw("""
                        <elementProp name="ThreadGroup.main_controller" elementType="LoopController" \
                        guiclass="LoopControlPanel" testclass="LoopController">
                        <boolProp name="LoopController.continue_forever">false</boolProp>
                        <intProp name="LoopController.loops">-1</intProp>
                        </elementProp>
                        """)
                .stringProp("ThreadGroup.on_sample_error", "continue")
                .endWithChildren();

        for (ScenarioSpec scenario : JmeterPlanCompiler.sortedScenarios(workload)) {
            writeScenario(jmx, workload, scenario, target, authentication);
        }

        jmx.endChildren();
    }

    /**
     * An open model: requests arriving at a stated rate, whatever the target does with them.
     *
     * <p>One Free-Form Arrivals Thread Group, whose schedule rows map one-to-one onto the
     * specification's stages — each row glides from the previous stage's rate to this one's over the
     * stage's duration. That correspondence is why this group is used rather than the fixed-shape
     * Arrivals Thread Group: a staged rate profile is expressible exactly, with no approximation to
     * explain afterwards.
     *
     * <p>{@code ConcurrencyLimit} is the specification's {@code maxConcurrency}, and it is the whole
     * reason an open model is safe to run. Without it a target that stalls makes arrivals accumulate
     * without bound until the generator exhausts its threads or its heap — and the run would report
     * the generator's death as the target's failure.
     */
    private static void writeArrivalsThreadGroup(JmxWriter jmx, WorkloadSpec workload,
            List<LoadStage> stages, ShardAssignment assignment, TargetAddress target,
            AuthenticationSpec authentication) {

        Integer ceiling = workload.model().maxConcurrency();
        if (ceiling == null || ceiling < 1) {
            // Unreachable for a validated specification, which is refused without a ceiling. Left
            // as a refusal rather than a default because the alternative is rendering an unbounded
            // open-model plan, and there is no safe number to invent.
            throw new IllegalArgumentException(
                    "An arrival-rate workload requires maxConcurrency before it can be rendered");
        }

        jmx.element("com.blazemeter.jmeter.threads.arrivals.FreeFormArrivalsThreadGroup",
                        attributes(
                                "com.blazemeter.jmeter.threads.arrivals.FreeFormArrivalsThreadGroupGui",
                                "com.blazemeter.jmeter.threads.arrivals.FreeFormArrivalsThreadGroup",
                                workload.name()))
                .raw("""
                        <elementProp name="ThreadGroup.main_controller" \
                        elementType="com.blazemeter.jmeter.control.VirtualUserController"/>
                        """)
                .stringProp("ThreadGroup.on_sample_error", "continue")
                .stringProp("Unit", "S")
                .stringProp("ConcurrencyLimit",
                        String.valueOf(rateForShard(ceiling, assignment)))
                .stringProp("Iterations", "")
                .stringProp("LogFilename", "")
                .raw("<collectionProp name=\"Schedule\">");

        int previousRate = 0;
        int row = 0;
        for (LoadStage stage : stages) {
            int stageRate = stage.targetRate() == null ? 0 : Math.max(0, stage.targetRate());
            long duration = stage.duration().toSeconds();

            jmx.raw("""
                    <collectionProp name="stage-%d">
                    <stringProp name="1">%d</stringProp>
                    <stringProp name="2">%d</stringProp>
                    <stringProp name="3">%d</stringProp>
                    </collectionProp>"""
                    .formatted(row++,
                            rateForShard(previousRate, assignment),
                            rateForShard(stageRate, assignment),
                            duration));
            previousRate = stageRate;
        }

        jmx.raw("</collectionProp>").endWithChildren();

        for (ScenarioSpec scenario : JmeterPlanCompiler.sortedScenarios(workload)) {
            writeScenario(jmx, workload, scenario, target, authentication);
        }

        jmx.endChildren();
    }

    /**
     * One scenario: a weighted controller holding its request.
     *
     * <p>The controller is what makes the mix real. Without it every request in the thread group
     * executes on every iteration, which is an even spread — the behaviour V1 had and the reason a
     * realistic journey mix could not be expressed.
     */
    private static void writeScenario(JmxWriter jmx, WorkloadSpec workload, ScenarioSpec scenario,
            TargetAddress target, AuthenticationSpec authentication) {
        jmx.element("ThroughputController", attributes("ThroughputControllerGui",
                        "ThroughputController", scenario.name()))
                .intProp("ThroughputController.style", PERCENT_EXECUTION)
                .boolProp("ThroughputController.perThread", false)
                .raw("""
                        <intProp name="ThroughputController.maxThroughput">1</intProp>
                        <FloatProperty>
                        <name>ThroughputController.percentThroughput</name>
                        <value>%d.0</value>
                        <savedValue>0.0</savedValue>
                        </FloatProperty>
                        """.formatted(scenario.weight()))
                .endWithChildren();

        // Every scenario is a sequence, and a single-request one is a sequence of length one.
        // Branching here instead would mean two rendering paths that drift apart.
        List<StepSpec> steps = scenario.asSteps();
        for (StepSpec step : steps) {
            // The label a single-request scenario has always had is preserved exactly:
            // {workload}/{scenario}. Only a real journey adds the step, because objectives are
            // scoped by these labels and renaming them would silently re-scope every stored
            // objective in every existing test.
            String label = scenario.isJourney()
                    ? workload.name() + "/" + scenario.name() + "/" + step.name()
                    : workload.name() + "/" + scenario.name();
            // Resolved here, not in the domain: falling back to /{operationId} for an unresolved
            // import is a rendering convention — deliberately obvious rather than plausible — and
            // only a single-request scenario has an operationId to fall back to.
            String path = scenario.isJourney() ? stepPath(step) : operationPath(scenario);
            writeStep(jmx, step, label, path, target, authentication);
        }

        jmx.endChildren();
    }

    /**
     * One request, with whatever it extracts, asserts and waits for.
     *
     * <p>The label is what JMeter writes into the results and the platform reads back. Objectives
     * are scoped by these labels, so the caller decides it: a single-request scenario keeps the
     * {@code {workload}/{scenario}} it has always had, and only a journey adds the step.
     */
    private static void writeStep(JmxWriter jmx, StepSpec step, String label, String path,
            TargetAddress target, AuthenticationSpec authentication) {

        boolean hasBody = step.hasBody();

        jmx.element("HTTPSamplerProxy", attributes("HttpTestSampleGui", "HTTPSamplerProxy", label))
                .raw(argumentsFor(step))
                .stringProp("HTTPSampler.domain", target.host())
                .stringProp("HTTPSampler.protocol", target.scheme())
                .longProp("HTTPSampler.port", target.port())
                .stringProp("HTTPSampler.path", path)
                // The step's own method where it states one, GET otherwise. A POST rendered as a GET
                // would measure a different endpoint than the one the test names.
                .stringProp("HTTPSampler.method", step.methodOrDefault())
                .boolProp("HTTPSampler.follow_redirects", true)
                .boolProp("HTTPSampler.use_keepalive", true)
                // Tells JMeter the arguments collection is a raw body rather than form fields.
                // Without it a JSON body is sent as an encoded form and the target sees nonsense.
                .boolProp("HTTPSampler.postBodyRaw", hasBody);

        boolean needsHeaders = authentication != null
                || !step.headers().isEmpty() || hasBody;
        boolean needsChildren = needsHeaders
                || !step.extract().isEmpty() || !step.expect().isEmpty() || step.thinkTime() != null;

        if (!needsChildren) {
            jmx.endLeaf();
            return;
        }

        jmx.endWithChildren();
        if (needsHeaders) {
            // A per-sampler HeaderManager, so one step's headers do not leak into another's. The
            // plan-level manager carries the run correlation id and applies to everything.
            writeStepHeaders(jmx, step, authentication);
        }
        step.extract().forEach(extraction -> writeExtraction(jmx, extraction));
        step.expect().forEach(assertion -> writeAssertion(jmx, assertion));
        // Placed after the request, because think time is what a person spends reading the response.
        if (step.thinkTime() != null && !step.thinkTime().isZero()) {
            writeThinkTime(jmx, step.thinkTime());
        }
        jmx.endChildren();
    }

    /**
     * A value pulled out of the response, and the guard that makes a failure visible.
     *
     * <p>This is the part that matters. When a pattern does not match, JMeter substitutes the
     * post-processor's default and <b>sends the next request anyway</b>. The target answers 4xx in a
     * couple of milliseconds, the run finishes early with a beautiful latency curve, and the report
     * describes a system that did no work. It is the same failure the platform already refuses in
     * five other places, reached from a new direction.
     *
     * <p>So every extraction renders two elements, never one: the post-processor with a
     * <b>sentinel</b> default, and a {@code ResponseAssertion} scoped to the variable that fails the
     * sample when the sentinel survived. Not optional and not configurable — an extraction whose
     * failure is silent is worse than no extraction at all, because the run still produces numbers.
     *
     * <p>An extraction marked {@code required: false} skips the assertion but keeps the sentinel, so
     * an unmatched optional value is still distinguishable from an empty one when someone reads the
     * request afterwards.
     */
    private static void writeExtraction(JmxWriter jmx, ExtractionSpec extraction) {
        String sentinel = sentinelFor(extraction.name());

        switch (extraction.from()) {
            case JSON_BODY -> jmx.element("JSONPostProcessor",
                            attributes("JSONPostProcessorGui", "JSONPostProcessor",
                                    "extract " + extraction.name()))
                    .stringProp("JSONPostProcessor.referenceNames", extraction.name())
                    .stringProp("JSONPostProcessor.jsonPathExprs", extraction.expression())
                    .stringProp("JSONPostProcessor.match_numbers", "1")
                    .stringProp("JSONPostProcessor.defaultValues", sentinel)
                    .endLeaf();

            case REGEX_BODY -> writeRegexExtractor(jmx, extraction, sentinel, "false", "body");
            // JMeter's field-to-check for headers, and for Set-Cookie specifically. Both are regular
            // expressions over the raw header block, which is why the cookie name is escaped into a
            // pattern rather than used directly.
            case HEADER -> writeRegexExtractor(jmx, extraction, sentinel, "true", "response_headers");
            case SET_COOKIE -> writeRegexExtractor(jmx, extraction, sentinel, "true",
                    "response_headers");
            case STATUS -> writeRegexExtractor(jmx, extraction, sentinel, "true", "response_code");
        }

        if (extraction.required()) {
            writeSentinelAssertion(jmx, extraction.name(), sentinel);
        }
    }

    /**
     * The sentinel a failed extraction leaves behind.
     *
     * <p>Deliberately unmistakable and deliberately not empty. An empty default is
     * indistinguishable from a value that was genuinely blank, and it produces a request that looks
     * merely odd rather than obviously wrong when somebody reads it back from the results.
     */
    static String sentinelFor(String name) {
        return "PERF_NOT_EXTRACTED_" + name;
    }

    private static void writeRegexExtractor(JmxWriter jmx, ExtractionSpec extraction,
            String sentinel, String useHeaders, String field) {

        String pattern = switch (extraction.from()) {
            case HEADER -> java.util.regex.Pattern.quote(extraction.expression()) + ":\\s*(.+)";
            case SET_COOKIE -> java.util.regex.Pattern.quote(extraction.expression()) + "=([^;]+)";
            case STATUS -> "(\\d+)";
            default -> extraction.expression();
        };

        jmx.element("RegexExtractor", attributes("RegexExtractorGui", "RegexExtractor",
                        "extract " + extraction.name()))
                .stringProp("RegexExtractor.useHeaders", useHeaders.equals("true") ? field : "false")
                .stringProp("RegexExtractor.refname", extraction.name())
                .stringProp("RegexExtractor.regex", pattern)
                .stringProp("RegexExtractor.template", "$1$")
                .stringProp("RegexExtractor.default", sentinel)
                .stringProp("RegexExtractor.match_number", "1")
                .endLeaf();
    }

    /**
     * Fails the sample when an extraction did not match.
     *
     * <p>A {@code ResponseAssertion} in variable scope, testing the extracted variable rather than
     * the response. {@code test_type} 12 is EQUALS (8) plus NOT (4) — the sample fails when the
     * variable equals the sentinel, which is exactly when nothing was extracted.
     */
    private static void writeSentinelAssertion(JmxWriter jmx, String name, String sentinel) {
        jmx.element("ResponseAssertion", attributes("AssertionGui", "ResponseAssertion",
                        name + " was extracted"))
                .stringProp("Assertion.scope", "variable")
                .stringProp("Scope.variable", name)
                .stringProp("Assertion.test_field", "Assertion.response_data")
                .boolProp("Assertion.assume_success", false)
                .intProp("Assertion.test_type", 12)
                .stringProp("Assertion.custom_message",
                        "Nothing was extracted for ${" + name + "}, so every request after this one "
                                + "would have been sent with an unfilled value.")
                .raw("""
                        <collectionProp name="Asserion.test_strings">
                        <stringProp name="0">%s</stringProp>
                        </collectionProp>
                        """.formatted(JmxWriter.escape(sentinel)))
                .endLeaf();
    }

    /** What must be true of the response, checked at run time. */
    private static void writeAssertion(JmxWriter jmx, AssertionSpec assertion) {
        // EQUALS 8, CONTAINS(substring) 16, MATCHES(regex) 1.
        int testType = switch (assertion.operator()) {
            case EQUALS -> 8;
            case CONTAINS -> 16;
            case MATCHES -> 1;
        };
        String field = assertion.subject() == AssertionSubject.STATUS
                ? "Assertion.response_code"
                : "Assertion.response_data";

        jmx.element("ResponseAssertion", attributes("AssertionGui", "ResponseAssertion",
                        "expect " + assertion.subject().name().toLowerCase(java.util.Locale.ROOT)))
                .stringProp("Assertion.test_field", field)
                .boolProp("Assertion.assume_success", false)
                .intProp("Assertion.test_type", testType)
                .raw("""
                        <collectionProp name="Asserion.test_strings">
                        <stringProp name="0">%s</stringProp>
                        </collectionProp>
                        """.formatted(JmxWriter.escape(assertion.value())))
                .endLeaf();
    }

    /**
     * The pause after a step.
     *
     * <p>A uniform random spread around the stated value rather than a fixed wait. Every virtual
     * user pausing for exactly the same time produces synchronised waves of traffic — a pattern no
     * real population makes, and one that measures the target's behaviour under a rhythm nobody
     * asked about.
     */
    private static void writeThinkTime(JmxWriter jmx, java.time.Duration thinkTime) {
        long millis = thinkTime.toMillis();
        // A tenth either side. Enough to break up the waves without making the stated pause a lie.
        long spread = Math.max(1, millis / 5);

        jmx.element("UniformRandomTimer", attributes("UniformRandomTimerGui", "UniformRandomTimer",
                        "think " + millis + "ms"))
                .stringProp("ConstantTimer.delay", String.valueOf(Math.max(0, millis - spread / 2)))
                .stringProp("RandomTimer.range", String.valueOf(spread))
                .endLeaf();
    }

    /**
     * The sampler's arguments: either a raw body, or the query parameters, or neither.
     *
     * <p>JMeter uses the same collection for both, which is why they are mutually exclusive here.
     * A body and a query string together would need the query in the path instead — worth doing when
     * something asks for it, and not worth guessing at now.
     */
    private static String argumentsFor(StepSpec scenario) {
        StringBuilder arguments = new StringBuilder(256);
        arguments.append("<elementProp name=\"HTTPsampler.Arguments\" elementType=\"Arguments\" ")
                .append("guiclass=\"HTTPArgumentsPanel\" testclass=\"Arguments\">\n")
                .append("<collectionProp name=\"Arguments.arguments\">\n");

        if (scenario.hasBody()) {
            // A raw body is one unnamed argument whose value is the whole payload. `always_encode`
            // must stay false or the payload is percent-encoded and the target receives gibberish.
            arguments.append("<elementProp name=\"\" elementType=\"HTTPArgument\">\n")
                    .append("<boolProp name=\"HTTPArgument.always_encode\">false</boolProp>\n")
                    .append("<stringProp name=\"Argument.value\">")
                    .append(JmxWriter.escape(scenario.body().content()))
                    .append("</stringProp>\n")
                    .append("<stringProp name=\"Argument.metadata\">=</stringProp>\n")
                    .append("</elementProp>\n");
        } else {
            scenario.query().forEach((name, value) -> arguments
                    .append("<elementProp name=\"").append(JmxWriter.escape(name))
                    .append("\" elementType=\"HTTPArgument\">\n")
                    // Encoded, because a query value may legitimately contain spaces or '&', and an
                    // unencoded one would silently change which parameters the target sees.
                    .append("<boolProp name=\"HTTPArgument.always_encode\">true</boolProp>\n")
                    .append("<stringProp name=\"Argument.name\">").append(JmxWriter.escape(name))
                    .append("</stringProp>\n")
                    .append("<stringProp name=\"Argument.value\">").append(JmxWriter.escape(value))
                    .append("</stringProp>\n")
                    .append("<stringProp name=\"Argument.metadata\">=</stringProp>\n")
                    .append("<boolProp name=\"HTTPArgument.use_equals\">true</boolProp>\n")
                    .append("</elementProp>\n"));
        }

        return arguments.append("</collectionProp>\n</elementProp>\n").toString();
    }

    /**
     * This scenario's own headers, plus the content type its body is sent as.
     *
     * <p>The content type is written here rather than left to JMeter's default, because the default
     * is none: a JSON body sent without {@code Content-Type} is rejected by most APIs, and the test
     * would measure the rejection rather than the endpoint.
     *
     * <p>A scenario that states its own {@code Content-Type} keeps it — the author was more specific
     * than the default, and overriding them would be the tool second-guessing the test.
     */
    private static void writeStepHeaders(JmxWriter jmx, StepSpec scenario,
            AuthenticationSpec authentication) {
        StringBuilder headers = new StringBuilder(256);
        headers.append("<collectionProp name=\"HeaderManager.headers\">\n");

        boolean declaresContentType = scenario.headers().keySet().stream()
                .anyMatch(name -> "content-type".equalsIgnoreCase(name.trim()));

        if (authentication != null) {
            headers.append("<elementProp name=\"\" elementType=\"Header\">\n")
                    .append("<stringProp name=\"Header.name\">")
                    .append(JmxWriter.escape(authentication.injectAs()))
                    .append("</stringProp>\n")
                    .append("<stringProp name=\"Header.value\">")
                    .append(JmxWriter.escape(authenticationValue(authentication)))
                    .append("</stringProp>\n")
                    .append("</elementProp>\n");
        }

        scenario.headers().forEach((name, value) -> headers
                .append("<elementProp name=\"\" elementType=\"Header\">\n")
                .append("<stringProp name=\"Header.name\">").append(JmxWriter.escape(name))
                .append("</stringProp>\n")
                .append("<stringProp name=\"Header.value\">").append(JmxWriter.escape(value))
                .append("</stringProp>\n")
                .append("</elementProp>\n"));

        if (scenario.hasBody() && !declaresContentType) {
            headers.append("<elementProp name=\"\" elementType=\"Header\">\n")
                    .append("<stringProp name=\"Header.name\">Content-Type</stringProp>\n")
                    .append("<stringProp name=\"Header.value\">")
                    .append(JmxWriter.escape(scenario.body().contentTypeOrDefault()))
                    .append("</stringProp>\n")
                    .append("</elementProp>\n");
        }

        headers.append("</collectionProp>\n");

        jmx.element("HeaderManager", attributes("HeaderPanel", "HeaderManager",
                        scenario.name() + " headers"))
                .raw(headers.toString())
                .endLeaf();
    }

    private static Map<String, AuthenticationSpec> authenticationsByName(
            PerformanceTestSpecification specification) {
        if (specification.spec() == null) {
            return Map.of();
        }
        Map<String, AuthenticationSpec> byName = new LinkedHashMap<>();
        specification.spec().authentication().stream()
                .filter(auth -> auth.name() != null && !auth.name().isBlank())
                .forEach(auth -> byName.putIfAbsent(auth.name(), auth));
        return byName;
    }

    private static String authenticationValue(AuthenticationSpec authentication) {
        if (isRunSessionAuth(authentication)) {
            return "${" + SESSION_VALUE_VARIABLE + "}";
        }
        return "${__env(" + RuntimeCredential.environmentVariableFor(authentication.name()) + ")}";
    }

    private static boolean isRunSessionAuth(AuthenticationSpec authentication) {
        return authentication.secretRef() != null
                && "run://session".equalsIgnoreCase(authentication.secretRef().trim());
    }

    private static void writeHeaderManager(JmxWriter jmx, ShardAssignment assignment) {
        jmx.element("HeaderManager", attributes("HeaderPanel", "HeaderManager",
                        "Run correlation"))
                .raw("""
                        <collectionProp name="HeaderManager.headers">
                        <elementProp name="" elementType="Header">
                        <stringProp name="Header.name">%s</stringProp>
                        <stringProp name="Header.value">%s</stringProp>
                        </elementProp>
                        </collectionProp>
                        """.formatted(
                                JmxWriter.escape(assignment.correlationHeader()),
                                JmxWriter.escape(assignment.correlationValue())))
                .endLeaf();
    }

    /**
     * The name a shard's partition of a dataset is written under, in the runner's workspace.
     *
     * <p>A plain file name, resolved relative to the working directory JMeter is started in. The
     * plan never contains an absolute path: a path that made sense on the control plane would be
     * meaningless inside a runner Pod, which is exactly the V1 defect that made stored report paths
     * useless.
     */
    public static String partitionFileName(String datasetName) {
        return datasetName + ".csv";
    }

    /**
     * One {@code CSVDataSet} per declared dataset, at plan level.
     *
     * <p>Plan level rather than per thread group, so a column is bound the same way wherever it is
     * referenced. {@code shareMode: all} matches that: every thread draws from one shared cursor, so
     * a row is used once per shard rather than once per thread group. Rows are <em>not</em> recycled
     * and a thread that runs out stops — the alternative is a test that quietly starts replaying the
     * first row under load, which reads as excellent latency because the target begins answering
     * from cache.
     *
     * <p>The header is read from the file rather than declared here, so the names a scenario binds
     * to are the names the file actually carries. The specification's own {@code columns} list is
     * what validation checks against, and the two are compared before the run starts.
     */
    private static void writeDatasets(JmxWriter jmx, PerformanceTestSpecification specification) {
        List<DatasetSpec> datasets = specification.spec() == null
                ? List.of() : specification.spec().datasets();

        for (DatasetSpec dataset : datasets) {
            if (dataset.name() == null || dataset.name().isBlank()) {
                continue;
            }
            jmx.element("CSVDataSet", attributes("TestBeanGUI", "CSVDataSet",
                            "Dataset " + dataset.name()))
                    .stringProp("filename", partitionFileName(dataset.name()))
                    .stringProp("fileEncoding", "UTF-8")
                    // Empty: JMeter then takes the variable names from the file's first line.
                    .stringProp("variableNames", "")
                    .boolProp("ignoreFirstLine", true)
                    .stringProp("delimiter", ",")
                    .boolProp("quotedData", true)
                    .boolProp("recycle", false)
                    .boolProp("stopThread", true)
                    .stringProp("shareMode", "shareMode.all")
                    .endLeaf();
        }
    }

    private static void writeSessionDataset(JmxWriter jmx,
            PerformanceTestSpecification specification) {
        if (specification.spec() == null) {
            return;
        }

        Map<String, AuthenticationSpec> authByName = authenticationsByName(specification);
        boolean usesPooledSession = specification.spec().workloads().stream()
                .map(WorkloadSpec::authRef)
                .filter(ref -> ref != null && !ref.isBlank())
                .map(authByName::get)
                .anyMatch(auth -> auth != null && isRunSessionAuth(auth));

        if (!usesPooledSession) {
            return;
        }

        jmx.element("CSVDataSet", attributes("TestBeanGUI", "CSVDataSet",
                        "Runtime sessions"))
                .stringProp("filename", SESSION_DATASET_FILE_NAME)
                .stringProp("fileEncoding", "UTF-8")
                .stringProp("variableNames", "")
                .boolProp("ignoreFirstLine", true)
                .stringProp("delimiter", ",")
                .boolProp("quotedData", true)
                .boolProp("recycle", false)
                .boolProp("stopThread", true)
                .stringProp("shareMode", "shareMode.all")
                .endLeaf();
    }

    private static void writeHttpDefaults(JmxWriter jmx, TargetAddress target) {
        jmx.element("ConfigTestElement", attributes("HttpDefaultsGui", "ConfigTestElement",
                        "HTTP Defaults"))
                .raw("""
                        <elementProp name="HTTPsampler.Arguments" elementType="Arguments" \
                        guiclass="HTTPArgumentsPanel" testclass="Arguments">
                        <collectionProp name="Arguments.arguments"/>
                        </elementProp>
                        """)
                .stringProp("HTTPSampler.domain", target.host())
                .stringProp("HTTPSampler.protocol", target.scheme())
                .longProp("HTTPSampler.port", target.port())
                .longProp("HTTPSampler.connect_timeout", 5_000)
                .longProp("HTTPSampler.response_timeout", 30_000)
                .endLeaf();
    }

    /**
     * How many threads this shard runs.
     *
     * <p>The stage's target divided across shards, with the remainder given to the lowest-indexed
     * shards. Rounding every shard up would overshoot the requested concurrency — with four shards
     * and a target of 10, rounding up runs 12 users against a system sized for 10.
     */
    static long threadsForShard(int targetUsers, ShardAssignment assignment) {
        int base = targetUsers / assignment.shardCount();
        int remainder = targetUsers % assignment.shardCount();
        return base + (assignment.shardIndex() < remainder ? 1 : 0);
    }

    /**
     * This shard's share of an arrival rate or a concurrency ceiling.
     *
     * <p>Divided exactly as users are, and for the same reason: the shards together must ask for
     * what the specification asked for. Rounding each shard up would send more traffic than the
     * document describes, and rounding each down would quietly send less — which is worse, because
     * the run would report a rate it never achieved as though the target could not keep up.
     */
    static long rateForShard(int total, ShardAssignment assignment) {
        return threadsForShard(total, assignment);
    }

    /**
     * How long the stage takes to reach its target concurrency.
     *
     * <p>A tenth of the stage, capped at 60 seconds. Ramping over the whole stage would mean the
     * stage never spends time at its target, and starting every thread at once produces a
     * connection storm that measures the system's accept queue rather than its behaviour under load.
     */
    static long rampSeconds(long stageDurationSeconds) {
        return Math.max(1, Math.min(60, stageDurationSeconds / 10));
    }

    /**
     * The path a scenario addresses.
     *
     * <p>A scenario that states its own {@code path} is executed as written — that is the manual
     * form, and it is what lets a specification address a real endpoint.
     *
     * <p>Otherwise the path is derived from the operation identifier, because no importer exists yet
     * to resolve one into a real path, method, headers or body (Increment 2). Deliberately obvious
     * rather than plausible: a generated plan that looked like it was hitting real endpoints when it
     * was not would be worse than one that plainly is not.
     *
     * <p>Only the path is taken from the specification. The scheme, host and port come from the
     * target the platform resolved, never from the document — which is what makes the target policy
     * an actual control rather than a suggestion.
     */
    /** A journey step's path, which validation has already required to begin with '/'. */
    private static String stepPath(StepSpec step) {
        if (step.path() == null || step.path().isBlank()) {
            return "/";
        }
        String path = step.path().trim();
        return path.startsWith("/") ? path : "/" + path;
    }

    private static String operationPath(ScenarioSpec scenario) {
        if (scenario.hasExplicitRequest()) {
            String path = scenario.path().trim();
            return path.startsWith("/") ? path : "/" + path;
        }

        String operationId = scenario.operationId();
        if (operationId == null || operationId.isBlank()) {
            return "/";
        }
        return operationId.startsWith("/") ? operationId : "/" + operationId;
    }

    private static String planName(PerformanceTestSpecification specification,
            ShardAssignment assignment) {
        String name = specification.metadata() == null ? "performance-test"
                : specification.metadata().name();
        return "%s (shard %d of %d)".formatted(
                name == null ? "performance-test" : name,
                assignment.shardIndex() + 1, assignment.shardCount());
    }

    private static String attributes(String guiClass, String testClass, String testName) {
        return "guiclass=\"%s\" testclass=\"%s\" testname=\"%s\" enabled=\"true\""
                .formatted(guiClass, testClass, JmxWriter.escape(testName));
    }
}
