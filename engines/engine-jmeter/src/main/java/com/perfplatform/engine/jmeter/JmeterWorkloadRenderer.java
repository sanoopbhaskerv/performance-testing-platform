package com.perfplatform.engine.jmeter;

import com.perfplatform.domain.plan.ExecutionPlan;
import com.perfplatform.domain.spec.PerformanceTestSpecification;
import com.perfplatform.domain.spec.PerformanceTestSpecification.LoadStage;
import com.perfplatform.domain.spec.PerformanceTestSpecification.ScenarioSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.WorkloadSpec;
import com.perfplatform.domain.target.TargetAddress;
import com.perfplatform.engine.spi.RenderedWorkload;
import com.perfplatform.engine.spi.ShardAssignment;

import java.util.List;

/**
 * Renders the JMX one shard executes.
 *
 * <h2>Scenario weighting</h2>
 * Each scenario becomes a {@code ThroughputController} in percent mode, carrying its weight. This is
 * what replaces V1's flattening (defect D4): scenario boundaries survive into the generated plan,
 * their names appear in the results so per-scenario objectives can be evaluated, and the traffic mix
 * is the one the consumer asked for rather than an even spread across every endpoint.
 *
 * <h2>Stages</h2>
 * Each stage becomes its own thread group, delayed by the stages before it. Vanilla JMeter's thread
 * group has one ramp and one duration and cannot express a multi-stage profile; sequencing thread
 * groups is the approximation available without plugins, and it is deliberately a <em>stepwise</em>
 * one — concurrency changes at stage boundaries rather than gliding between them.
 *
 * <p>This is documented rather than hidden because it affects what the numbers mean: a stepwise
 * profile stresses a system differently from a smooth ramp at the transition. A true concurrency
 * profile needs the {@code ConcurrencyThreadGroup} plugin in the runner image, which is a runner
 * packaging decision that belongs with the Kubernetes provider in Increment 1E.
 *
 * <h2>How a scenario becomes a request</h2>
 * A scenario that states its own {@code method} and {@code path} is rendered as written — the manual
 * form, and what lets a specification address a real endpoint today. Only the path comes from the
 * document; scheme, host and port come from the target the platform resolved, so a specification
 * still cannot choose where its traffic goes.
 *
 * <h2>What this cannot do yet</h2>
 * A scenario that instead names an {@code operationId} refers to an imported source, and no importer
 * exists until Increment 2. Until then such samplers address {@code /{operationId}}, which exercises
 * the whole pipeline but does not reproduce a real request. Headers, query parameters and request
 * bodies are not yet expressible at all — also Increment 2. Both limits are visible in the generated
 * plan rather than papered over.
 */
final class JmeterWorkloadRenderer {

    /** Percent-execution mode for {@code ThroughputController}. */
    private static final int PERCENT_EXECUTION = 1;

    private JmeterWorkloadRenderer() {
    }

    /**
     * @param target the base URL this shard sends traffic to, resolved server-side. Never taken
     *               from the specification: a document that could name its own target would route
     *               around the allowlist it was validated against
     */
    static RenderedWorkload render(PerformanceTestSpecification specification, ExecutionPlan plan,
            ShardAssignment assignment, TargetAddress target) {

        List<WorkloadSpec> workloads = JmeterPlanCompiler.protocolWorkloads(specification);
        if (workloads.isEmpty()) {
            throw new IllegalArgumentException("No protocol workload to render");
        }

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

        for (WorkloadSpec workload : workloads) {
            writeWorkload(jmx, workload, assignment, target);
        }

        jmx.endChildren();

        return new RenderedWorkload(
                "shard-%d.jmx".formatted(assignment.shardIndex()),
                "application/xml",
                jmx.build());
    }

    private static void writeWorkload(JmxWriter jmx, WorkloadSpec workload,
            ShardAssignment assignment, TargetAddress target) {

        List<LoadStage> stages = JmeterPlanCompiler.stages(workload);
        if (stages.isEmpty()) {
            return;
        }

        long offsetSeconds = 0;
        for (int index = 0; index < stages.size(); index++) {
            LoadStage stage = stages.get(index);
            long durationSeconds = stage.duration().toSeconds();

            // A stage targeting zero users is a ramp-down instruction, not a thread group. Emitting
            // one with no threads would produce a group JMeter starts and immediately ends.
            if (stage.targetUsers() > 0) {
                writeStageThreadGroup(jmx, workload, stage, index, offsetSeconds, durationSeconds,
                        assignment, target);
            }
            offsetSeconds += durationSeconds;
        }
    }

    private static void writeStageThreadGroup(JmxWriter jmx, WorkloadSpec workload, LoadStage stage,
            int stageIndex, long delaySeconds, long durationSeconds, ShardAssignment assignment,
            TargetAddress target) {

        jmx.element("ThreadGroup", attributes("ThreadGroupGui", "ThreadGroup",
                        "%s / stage %d".formatted(workload.name(), stageIndex + 1)))
                .stringProp("ThreadGroup.on_sample_error", "continue")
                .raw("""
                        <elementProp name="ThreadGroup.main_controller" elementType="LoopController" \
                        guiclass="LoopControlPanel" testclass="LoopController">
                        <boolProp name="LoopController.continue_forever">false</boolProp>
                        <intProp name="LoopController.loops">-1</intProp>
                        </elementProp>
                        """)
                // Written as a number, never through a formatter. This is the line V1 corrupted at
                // 1,000 users — exactly the platform's stated maximum (defect D1).
                .longProp("ThreadGroup.num_threads", threadsForShard(stage.targetUsers(), assignment))
                .longProp("ThreadGroup.ramp_time", rampSeconds(durationSeconds))
                .boolProp("ThreadGroup.scheduler", true)
                .longProp("ThreadGroup.duration", durationSeconds)
                .longProp("ThreadGroup.delay", delaySeconds)
                .boolProp("ThreadGroup.same_user_on_next_iteration", true)
                .endWithChildren();

        for (ScenarioSpec scenario : JmeterPlanCompiler.sortedScenarios(workload)) {
            writeScenario(jmx, workload, scenario, target);
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
            TargetAddress target) {
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

        writeSampler(jmx, workload, scenario, target);

        jmx.endChildren();
    }

    private static void writeSampler(JmxWriter jmx, WorkloadSpec workload, ScenarioSpec scenario,
            TargetAddress target) {

        // Named {workload}/{scenario}, because that is the label JMeter writes into the results and
        // the platform reads back. Objectives are scoped by workload and by scenario, so both parts
        // have to be in the name for either kind of objective to be evaluable. V1 wrote neither.
        jmx.element("HTTPSamplerProxy", attributes("HttpTestSampleGui", "HTTPSamplerProxy",
                        workload.name() + "/" + scenario.name()))
                .raw("""
                        <elementProp name="HTTPsampler.Arguments" elementType="Arguments" \
                        guiclass="HTTPArgumentsPanel" testclass="Arguments">
                        <collectionProp name="Arguments.arguments"/>
                        </elementProp>
                        """)
                .stringProp("HTTPSampler.domain", target.host())
                .stringProp("HTTPSampler.protocol", target.scheme())
                .longProp("HTTPSampler.port", target.port())
                .stringProp("HTTPSampler.path", operationPath(scenario))
                // The scenario's own method where it states one, GET otherwise. A POST rendered as a
                // GET would measure a different endpoint than the one the test names.
                .stringProp("HTTPSampler.method", scenario.methodOrDefault())
                .boolProp("HTTPSampler.follow_redirects", true)
                .boolProp("HTTPSampler.use_keepalive", true)
                .endLeaf();
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
