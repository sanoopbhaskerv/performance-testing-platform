package com.perfplatform.engine.jmeter;

import com.perfplatform.domain.plan.ExecutionPlan;
import com.perfplatform.domain.spec.PerformanceTestSpecification;
import com.perfplatform.engine.spi.RenderedWorkload;
import com.perfplatform.engine.spi.ShardAssignment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The generated JMeter plan.
 *
 * <p>Several of these pin baseline defects as fixed. Each names the defect it closes, so a future
 * change that reintroduces one fails with an explanation rather than a diff.
 */
class JmeterWorkloadRendererTest {

    private static String render(PerformanceTestSpecification specification, ShardAssignment shard) {
        ExecutionPlan plan = JmeterFixtures.planFor(specification);
        return JmeterWorkloadRenderer
                .render(specification, plan, shard, JmeterFixtures.TARGET)
                .content();
    }

    private static String renderDefault(PerformanceTestSpecification specification) {
        return render(specification, JmeterFixtures.shard(0, 1));
    }

    @Nested
    @DisplayName("defect D1 — numeric corruption at 1,000 and above")
    class NumericFormatting {

        /**
         * V1 rendered numbers through Freemarker, which applies locale-aware digit grouping: 1,000
         * virtual users became the text {@code 1,000} and JMeter could not parse it. The platform's
         * stated maximum is 1,000 users, so V1 could not run a test at the scale it was specified
         * for. Numbers no longer pass through a formatter at all.
         */
        @Test
        @DisplayName("a thread count of 1,000 renders without digit grouping")
        void thousandUsersRenderPlainly() {
            String jmx = renderDefault(JmeterFixtures.atMaximumConcurrency());

            // The layer's thread count, in the Ultimate Thread Group's first column.
            assertThat(jmx).contains("<stringProp name=\"1\">1000</stringProp>");
            assertThat(jmx).doesNotContain("1,000");
        }

        @Test
        @DisplayName("a duration of an hour renders without digit grouping")
        void longDurationsRenderPlainly() {
            String jmx = renderDefault(JmeterFixtures.atMaximumConcurrency());

            // 3600, the value V1 rendered as "3,600" — its documented default endurance duration.
            // Now the layer's ramp, since a single stage glides across its whole duration.
            assertThat(jmx).contains("<stringProp name=\"3\">3600</stringProp>");
            assertThat(jmx).doesNotContain("3,600");
        }

        @Test
        @DisplayName("no grouped number appears anywhere in the plan")
        void noGroupedNumbersAtAll() {
            assertThat(renderDefault(JmeterFixtures.atMaximumConcurrency()))
                    .doesNotContainPattern("\\d,\\d{3}");
        }
    }

    @Nested
    @DisplayName("defect D4 — scenario boundaries and weights")
    class ScenarioPreservation {

        /**
         * V1 flattened every scenario into one endpoint list, so scenario names did not appear in
         * the generated plan and no weighting mechanism existed.
         */
        @Test
        @DisplayName("every scenario appears by name")
        void scenarioNamesSurvive() {
            String jmx = renderDefault(JmeterFixtures.twoStagesThreeScenarios());

            assertThat(jmx)
                    .contains("search-client")
                    .contains("open-proposal")
                    .contains("save-proposal");
        }

        @Test
        @DisplayName("each scenario becomes a weighted controller carrying its share")
        void weightsBecomeControllers() {
            String jmx = renderDefault(JmeterFixtures.twoStagesThreeScenarios());

            assertThat(jmx).contains("ThroughputController");
            assertThat(jmx).contains("<value>60.0</value>");
            assertThat(jmx).contains("<value>30.0</value>");
            assertThat(jmx).contains("<value>10.0</value>");
        }

        /**
         * The label is {@code workload/scenario}, not the scenario alone.
         *
         * <p>Both parts are needed. JMeter writes this name into the results, and objectives are
         * scoped by workload <em>and</em> by scenario — a label carrying only the scenario makes a
         * workload-scoped objective unresolvable, and one carrying only the workload makes a
         * scenario-scoped objective unresolvable. V1 wrote neither.
         */
        @Test
        @DisplayName("a sampler is named workload/scenario, so both kinds of objective can be judged")
        void samplersCarryWorkloadAndScenario() {
            assertThat(renderDefault(JmeterFixtures.twoStagesThreeScenarios()))
                    .contains("testclass=\"HTTPSamplerProxy\" testname=\"api-load/search-client\"")
                    .contains("testname=\"api-load/open-proposal\"")
                    .contains("testname=\"api-load/save-proposal\"");
        }
    }

    /**
     * The load shape, which S8 changed on purpose.
     *
     * <p>A staged profile used to become one vanilla thread group per stage, each starting from
     * zero, so concurrency <b>stepped</b> at every boundary: the previous stage's threads stopped
     * and the next stage's started. It now becomes one Ultimate Thread Group whose layers sum to the
     * curve, so concurrency <b>glides</b>. That is a different test at every transition, which is
     * why these assertions were rewritten rather than adjusted.
     */
    @Nested
    @DisplayName("workload stages — a gliding concurrency profile")
    class Stages {

        @Test
        @DisplayName("the whole profile is one thread group, not one per stage")
        void oneThreadGroupForTheProfile() {
            String jmx = renderDefault(JmeterFixtures.twoStagesThreeScenarios());

            assertThat(countOf(jmx, "testclass=\"kg.apc.jmeter.threads.UltimateThreadGroup\""))
                    .isEqualTo(1);
            // And none of the vanilla groups that used to step between stages.
            assertThat(jmx).doesNotContain("testclass=\"ThreadGroup\"");
        }

        @Test
        @DisplayName("a rise becomes a layer that starts where the previous stage ended")
        void risesBecomeDelayedLayers() {
            String jmx = renderDefault(JmeterFixtures.twoStagesThreeScenarios());

            // 0 → 100 over five minutes, then 100 → 200 over ten. The second layer carries the
            // difference and starts where the first stage ended — their sum is the requested curve.
            // The first layer holds through the second stage rather than ending with it: those
            // hundred users are still there while the next hundred arrive. That is the glide.
            assertThat(layersIn(jmx))
                    .containsExactly(
                            List.of(100L, 0L, 300L, 600L, 0L),
                            List.of(100L, 300L, 600L, 0L, 0L));
        }

        /**
         * The point of the change. A boundary between two stages is a ramp, not a restart.
         */
        @Test
        @DisplayName("concurrency glides through a boundary rather than stepping at it")
        void concurrencyGlidesAcrossTheBoundary() {
            var stages = java.util.List.of(
                    new PerformanceTestSpecification.LoadStage(java.time.Duration.ofSeconds(60), 100),
                    new PerformanceTestSpecification.LoadStage(java.time.Duration.ofSeconds(60), 200));

            var layers = ConcurrencyProfile.layersFor(stages);

            // Halfway through the second stage the profile sits between the two levels, which a
            // stepwise rendering could never produce: it would read 100 until the boundary and 200
            // after it.
            assertThat(ConcurrencyProfile.concurrencyAt(layers, 60)).isEqualTo(100);
            assertThat(ConcurrencyProfile.concurrencyAt(layers, 90)).isBetween(140, 160);
            assertThat(ConcurrencyProfile.concurrencyAt(layers, 119)).isBetween(190, 200);
        }

        /**
         * A stage targeting zero users expresses ramp-down. It is now the shutdown of the layers
         * already standing rather than an omitted thread group, so the descent is a ramp and not a
         * cliff.
         */
        @Test
        @DisplayName("a ramp-down stage becomes the layer's shutdown, not a missing group")
        void rampDownBecomesAShutdown() {
            PerformanceTestSpecification withRampDown = JmeterFixtures.specification(
                    java.util.List.of(JmeterFixtures.workload("api-load",
                            java.util.List.of(
                                    new PerformanceTestSpecification.LoadStage(
                                            java.time.Duration.ofMinutes(5), 100),
                                    new PerformanceTestSpecification.LoadStage(
                                            java.time.Duration.ofMinutes(2), 0)),
                            java.util.List.of(new PerformanceTestSpecification.ScenarioSpec(
                                    "search-client", 100, "searchClient")))));

            String jmx = renderDefault(withRampDown);

            assertThat(countOf(jmx, "testclass=\"kg.apc.jmeter.threads.UltimateThreadGroup\""))
                    .isEqualTo(1);
            // 100 threads, no delay, five-minute rise, no hold, two-minute descent.
            assertThat(layersIn(jmx)).containsExactly(List.of(100L, 0L, 300L, 0L, 120L));
        }
    }

    /**
     * How a scenario becomes an actual HTTP request.
     *
     * <p>This is what lets a specification address a real endpoint before importers exist. A test
     * declaring {@code POST /api/payments} that rendered as {@code GET /submitPayment} would measure
     * something other than the thing it names, and the report would describe a system nobody tested.
     */
    @Nested
    @DisplayName("a scenario's request")
    class Requests {

        private static PerformanceTestSpecification withScenarios(
                java.util.List<PerformanceTestSpecification.ScenarioSpec> scenarios) {
            return JmeterFixtures.specification(java.util.List.of(
                    JmeterFixtures.workload("api-load",
                            java.util.List.of(new PerformanceTestSpecification.LoadStage(
                                    java.time.Duration.ofMinutes(1), 10)),
                            scenarios)));
        }

        @Test
        @DisplayName("a scenario's own method and path are what the sampler calls")
        void explicitMethodAndPathAreRendered() {
            String jmx = renderDefault(withScenarios(java.util.List.of(
                    new PerformanceTestSpecification.ScenarioSpec(
                            "browse-users", 80, null, "GET", "/api/users"),
                    new PerformanceTestSpecification.ScenarioSpec(
                            "submit-payment", 20, null, "POST", "/api/payments"))));

            assertThat(jmx).contains("<stringProp name=\"HTTPSampler.path\">/api/users</stringProp>");
            assertThat(jmx).contains("<stringProp name=\"HTTPSampler.method\">GET</stringProp>");
            assertThat(jmx).contains("<stringProp name=\"HTTPSampler.path\">/api/payments</stringProp>");
            assertThat(jmx).contains("<stringProp name=\"HTTPSampler.method\">POST</stringProp>");
        }

        @Test
        @DisplayName("pooled runtime session authentication reads the session dataset, never a value")
        void sessionAuthenticationUsesDatasetLookup() {
            String jmx = renderDefault(credentialed("session", "run://session"));

            assertThat(jmx)
                    .contains("<stringProp name=\"Header.name\">Authorization</stringProp>")
                    .contains("<stringProp name=\"Header.value\">${session_value}</stringProp>")
                    .contains("<stringProp name=\"filename\">sessions.csv</stringProp>")
                    .doesNotContain("run://session")
                    .doesNotContain("Bearer ");
        }

        @Test
        @DisplayName("non-session runtime authentication still uses environment lookup")
        void nonSessionAuthenticationUsesEnvironmentLookup() {
            String jmx = renderDefault(credentialed("api-key", "run://apiKey"));

            assertThat(jmx)
                    .contains("<stringProp name=\"Header.name\">Authorization</stringProp>")
                    .contains("<stringProp name=\"Header.value\">${__env(PERF_AUTH_API_KEY)}</stringProp>")
                    .doesNotContain("<stringProp name=\"filename\">sessions.csv</stringProp>")
                    .doesNotContain("run://apiKey");
        }

        @Test
        @DisplayName("a method is normalized to upper case rather than passed through as written")
        void methodIsNormalized() {
            String jmx = renderDefault(withScenarios(java.util.List.of(
                    new PerformanceTestSpecification.ScenarioSpec(
                            "pay", 100, null, "post", "/api/payments"))));

            assertThat(jmx).contains("<stringProp name=\"HTTPSampler.method\">POST</stringProp>");
        }

        /**
         * Only the path comes from the specification. Scheme, host and port come from the target the
         * platform resolved, which is what makes the target policy a control rather than a suggestion.
         */
        @Test
        @DisplayName("the host still comes from the resolved target, never from the specification")
        void hostComesFromTheResolvedTarget() {
            String jmx = renderDefault(withScenarios(java.util.List.of(
                    new PerformanceTestSpecification.ScenarioSpec(
                            "browse", 100, null, "GET", "/api/users"))));

            assertThat(jmx).contains("<stringProp name=\"HTTPSampler.domain\">"
                    + JmeterFixtures.TARGET.host() + "</stringProp>");
        }

        @Test
        @DisplayName("query parameters are rendered as encoded arguments")
        void queryParametersAreRendered() {
            String jmx = renderDefault(withScenarios(java.util.List.of(
                    new PerformanceTestSpecification.ScenarioSpec(
                            "browse", 100, null, "GET", "/api/users",
                            java.util.Map.of(),
                            new java.util.LinkedHashMap<>(java.util.Map.of("page", "1")),
                            null))));

            assertThat(jmx).contains("<stringProp name=\"Argument.name\">page</stringProp>");
            assertThat(jmx).contains("<stringProp name=\"Argument.value\">1</stringProp>");
            // Encoded, because a value may legitimately contain a space or an ampersand, and an
            // unencoded one would silently change which parameters the target sees.
            assertThat(jmx).contains("<boolProp name=\"HTTPArgument.always_encode\">true</boolProp>");
        }

        @Test
        @DisplayName("a body is sent raw, with the content type the scenario declares")
        void bodyIsSentRaw() {
            String jmx = renderDefault(withScenarios(java.util.List.of(
                    new PerformanceTestSpecification.ScenarioSpec(
                            "pay", 100, null, "POST", "/api/payments",
                            java.util.Map.of(), java.util.Map.of(),
                            new PerformanceTestSpecification.ScenarioSpec.RequestBody(
                                    "application/json", "{\"amount\":100}")))));

            // Raw, not form-encoded. Without this a JSON body is sent as an encoded form and the
            // target receives nonsense.
            assertThat(jmx).contains("<boolProp name=\"HTTPSampler.postBodyRaw\">true</boolProp>");
            assertThat(jmx).contains("{&quot;amount&quot;:100}");
            assertThat(jmx).contains("<boolProp name=\"HTTPArgument.always_encode\">false</boolProp>");
        }

        /**
         * JMeter sends no {@code Content-Type} by default. A JSON body without one is rejected by
         * most APIs, so the test would measure the rejection rather than the endpoint.
         */
        @Test
        @DisplayName("a body gets a Content-Type header even when the scenario sets no headers")
        void bodyCarriesItsContentType() {
            String jmx = renderDefault(withScenarios(java.util.List.of(
                    new PerformanceTestSpecification.ScenarioSpec(
                            "pay", 100, null, "POST", "/api/payments",
                            java.util.Map.of(), java.util.Map.of(),
                            new PerformanceTestSpecification.ScenarioSpec.RequestBody(
                                    null, "{}")))));

            assertThat(jmx).contains("<stringProp name=\"Header.name\">Content-Type</stringProp>");
            assertThat(jmx).contains("<stringProp name=\"Header.value\">application/json</stringProp>");
        }

        /** The author was more specific than the default; overriding them would second-guess the test. */
        @Test
        @DisplayName("a scenario's own Content-Type is not overridden by the default")
        void explicitContentTypeWins() {
            String jmx = renderDefault(withScenarios(java.util.List.of(
                    new PerformanceTestSpecification.ScenarioSpec(
                            "pay", 100, null, "POST", "/api/payments",
                            java.util.Map.of("Content-Type", "application/vnd.api+json"),
                            java.util.Map.of(),
                            new PerformanceTestSpecification.ScenarioSpec.RequestBody(
                                    "application/json", "{}")))));

            assertThat(jmx).contains("application/vnd.api+json");
            assertThat(countOf(jmx, "<stringProp name=\"Header.name\">Content-Type</stringProp>"))
                    .isEqualTo(1);
        }

        /**
         * A per-sampler HeaderManager, so one scenario's headers do not reach another's requests.
         * A shared one would send a JSON content type on a scenario posting form data.
         */
        @Test
        @DisplayName("headers belong to their own scenario, not to every request in the plan")
        void headersAreScopedToTheirScenario() {
            String jmx = renderDefault(withScenarios(java.util.List.of(
                    new PerformanceTestSpecification.ScenarioSpec(
                            "browse", 50, null, "GET", "/api/users",
                            java.util.Map.of("Accept", "application/json"),
                            java.util.Map.of(), null),
                    new PerformanceTestSpecification.ScenarioSpec(
                            "health", 50, null, "GET", "/health",
                            java.util.Map.of(), java.util.Map.of(), null))));

            // One scenario declares a header, the other declares none — so exactly one scenario-level
            // HeaderManager, plus the plan-level one carrying the run correlation id.
            assertThat(countOf(jmx, "testclass=\"HeaderManager\"")).isEqualTo(2);
            assertThat(jmx).contains("browse headers");
        }

        /**
         * The unimported case. Deliberately obvious rather than plausible: a plan that looked like it
         * was hitting a real endpoint when no importer had resolved one would be worse than a plan
         * that plainly is not.
         */
        @Test
        @DisplayName("a scenario with only an operationId still addresses /{operationId} as GET")
        void operationIdFallbackIsUnchanged() {
            String jmx = renderDefault(withScenarios(java.util.List.of(
                    new PerformanceTestSpecification.ScenarioSpec("search", 100, "searchClient"))));

            assertThat(jmx).contains("<stringProp name=\"HTTPSampler.path\">/searchClient</stringProp>");
            assertThat(jmx).contains("<stringProp name=\"HTTPSampler.method\">GET</stringProp>");
        }
    }

    private static PerformanceTestSpecification credentialed(String authName, String secretRef) {
        PerformanceTestSpecification base = JmeterFixtures.specification(java.util.List.of(
                JmeterFixtures.workload("api-load",
                        java.util.List.of(new PerformanceTestSpecification.LoadStage(
                                java.time.Duration.ofMinutes(1), 10)),
                        java.util.List.of(new PerformanceTestSpecification.ScenarioSpec(
                                "browse-users", 100, null, "GET", "/api/users")))));
        var body = base.spec();
        var workload = body.workloads().get(0);
        return new PerformanceTestSpecification(
                base.apiVersion(), base.kind(), base.metadata(),
                new PerformanceTestSpecification.TestSpec(
                        body.environment(), body.sources(),
                        java.util.List.of(new PerformanceTestSpecification.AuthenticationSpec(
                                authName, "bearer", "Authorization", secretRef)),
                        body.datasets(),
                        java.util.List.of(new PerformanceTestSpecification.WorkloadSpec(
                                workload.name(), workload.type(), workload.engine(),
                                workload.sourceRef(), authName, workload.model(),
                                workload.scenarios(), workload.startWhen(),
                                workload.browserUsers(), workload.iterationsPerUser())),
                        body.objectives(), body.observability(), body.governance()));
    }

    @Nested
    @DisplayName("run correlation")
    class Correlation {

        /**
         * Without this, application-side observability cannot separate test traffic from real
         * traffic — the difference between a diagnosable result and a number.
         */
        @Test
        @DisplayName("every request carries the run identifier in the correlation header")
        void correlationHeaderIsInjected() {
            ShardAssignment shard = JmeterFixtures.shard(0, 1);
            String jmx = render(JmeterFixtures.twoStagesThreeScenarios(), shard);

            assertThat(jmx).contains("<stringProp name=\"Header.name\">X-Performance-Test-Run-Id</stringProp>");
            assertThat(jmx).contains("<stringProp name=\"Header.value\">"
                    + shard.runId() + "</stringProp>");
        }

        @Test
        @DisplayName("a specification may name its own correlation header")
        void customCorrelationHeader() {
            ShardAssignment shard = new ShardAssignment(
                    com.perfplatform.domain.identity.TestRunId.generate(), 0, 1, "X-Trace-Id");

            assertThat(render(JmeterFixtures.twoStagesThreeScenarios(), shard))
                    .contains("<stringProp name=\"Header.name\">X-Trace-Id</stringProp>");
        }
    }

    @Nested
    @DisplayName("sharding")
    class Sharding {

        /**
         * Rounding every shard up would overshoot: four shards and a target of 10 would run 12
         * users against a system sized for 10.
         */
        @Test
        @DisplayName("concurrency is divided across shards without overshooting the target")
        void concurrencyIsDivided() {
            long total = 0;
            for (int index = 0; index < 4; index++) {
                total += JmeterWorkloadRenderer.threadsForShard(10, JmeterFixtures.shard(index, 4));
            }
            assertThat(total).isEqualTo(10);
        }

        @Test
        @DisplayName("the remainder goes to the lowest-indexed shards")
        void remainderGoesToEarlyShards() {
            assertThat(JmeterWorkloadRenderer.threadsForShard(10, JmeterFixtures.shard(0, 4)))
                    .isEqualTo(3);
            assertThat(JmeterWorkloadRenderer.threadsForShard(10, JmeterFixtures.shard(3, 4)))
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("a single shard runs the whole target")
        void singleShardRunsEverything() {
            assertThat(JmeterWorkloadRenderer.threadsForShard(200, JmeterFixtures.shard(0, 1)))
                    .isEqualTo(200);
        }

        @Test
        @DisplayName("each shard's plan is named for the shard it belongs to")
        void shardsAreIdentifiable() {
            assertThat(render(JmeterFixtures.twoStagesThreeScenarios(), JmeterFixtures.shard(1, 4)))
                    .contains("(shard 2 of 4)");
        }
    }

    @Nested
    @DisplayName("structure and determinism")
    class Structure {

        @Test
        @DisplayName("the document is well formed and balanced")
        void documentIsBalanced() {
            String jmx = renderDefault(JmeterFixtures.twoStagesThreeScenarios());

            assertThat(jmx).startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
            assertThat(jmx).endsWith("</jmeterTestPlan>\n");
            // Every element has a sibling hashTree, so JMeter cannot mis-parse the nesting.
            assertThat(countOf(jmx, "<hashTree>") + countOf(jmx, "<hashTree/>"))
                    .isEqualTo(countOf(jmx, "</hashTree>") + countOf(jmx, "<hashTree/>"));
        }

        @Test
        @DisplayName("rendering twice produces identical bytes")
        void renderingIsDeterministic() {
            PerformanceTestSpecification specification = JmeterFixtures.twoStagesThreeScenarios();
            ExecutionPlan plan = JmeterFixtures.planFor(specification);
            ShardAssignment shard = JmeterFixtures.shard(0, 1);

            RenderedWorkload first =
                    JmeterWorkloadRenderer.render(specification, plan, shard, JmeterFixtures.TARGET);
            RenderedWorkload second =
                    JmeterWorkloadRenderer.render(specification, plan, shard, JmeterFixtures.TARGET);

            assertThat(second.content()).isEqualTo(first.content());
        }

        @Test
        @DisplayName("the target comes from the resolver, never from the specification")
        void targetComesFromTheResolver() {
            String jmx = renderDefault(JmeterFixtures.twoStagesThreeScenarios());

            assertThat(jmx).contains(
                    "<stringProp name=\"HTTPSampler.domain\">proposal.example.internal</stringProp>");
            assertThat(jmx).contains("<stringProp name=\"HTTPSampler.protocol\">https</stringProp>");
            assertThat(jmx).contains("<stringProp name=\"HTTPSampler.port\">443</stringProp>");
        }

        @Test
        @DisplayName("a scenario name containing XML metacharacters is escaped")
        void namesAreEscaped() {
            PerformanceTestSpecification awkward = JmeterFixtures.specification(
                    java.util.List.of(JmeterFixtures.workload("api-load",
                            java.util.List.of(new PerformanceTestSpecification.LoadStage(
                                    java.time.Duration.ofMinutes(1), 5)),
                            java.util.List.of(new PerformanceTestSpecification.ScenarioSpec(
                                    "search & \"quote\" <tag>", 100, "searchClient")))));

            String jmx = renderDefault(awkward);

            assertThat(jmx).contains("search &amp; &quot;quote&quot; &lt;tag&gt;");
            assertThat(jmx).doesNotContain("search & \"quote\" <tag>");
        }
    }

    /**
     * Datasets, which are what let a test send something other than one repeated request.
     */
    @Nested
    @DisplayName("datasets")
    class Datasets {

        @Test
        @DisplayName("each declared dataset becomes a CSVDataSet reading its shard's partition")
        void rendersACsvDataSet() {
            String jmx = renderDefault(JmeterFixtures.withDataset());

            assertThat(jmx).contains("testclass=\"CSVDataSet\"");
            assertThat(jmx).contains("<stringProp name=\"filename\">proposals.csv</stringProp>");
        }

        /**
         * A relative name, resolved against the runner's working directory. An absolute path made
         * on the control plane means nothing inside a runner Pod — the V1 defect that made stored
         * report paths unopenable.
         */
        @Test
        @DisplayName("the dataset is referenced by file name, never by an absolute path")
        void referencesTheFileByNameOnly() {
            String jmx = renderDefault(JmeterFixtures.withDataset());

            assertThat(jmx).doesNotContain("<stringProp name=\"filename\">/");
        }

        /**
         * Column names come from the file's own header. Declaring them here as well would be a
         * second source of truth, and a re-exported file would bind values to the wrong names.
         */
        @Test
        @DisplayName("variable names are taken from the file's header, not restated in the plan")
        void takesColumnNamesFromTheFile() {
            String jmx = renderDefault(JmeterFixtures.withDataset());

            assertThat(jmx).contains("<stringProp name=\"variableNames\"></stringProp>");
            assertThat(jmx).contains("<boolProp name=\"ignoreFirstLine\">true</boolProp>");
        }

        /**
         * Recycling would restart the file under load, so the target begins answering the same
         * requests from cache and the run reports excellent latency for work it stopped doing.
         */
        @Test
        @DisplayName("rows are not recycled when a shard runs out")
        void doesNotRecycleRows() {
            String jmx = renderDefault(JmeterFixtures.withDataset());

            assertThat(jmx).contains("<boolProp name=\"recycle\">false</boolProp>");
            assertThat(jmx).contains("<boolProp name=\"stopThread\">true</boolProp>");
        }

        /** One shared cursor per shard, so a row is used once rather than once per thread group. */
        @Test
        @DisplayName("every thread group draws from one shared cursor")
        void sharesOneCursor() {
            assertThat(renderDefault(JmeterFixtures.withDataset()))
                    .contains("<stringProp name=\"shareMode\">shareMode.all</stringProp>");
        }

        /**
         * {@code ${name}} is JMeter's own variable syntax, so a bound value needs no rewriting.
         * The reference must survive into the sampler exactly as written.
         */
        @Test
        @DisplayName("a column reference reaches the request untouched")
        void passesReferencesThrough() {
            String jmx = renderDefault(JmeterFixtures.withDataset());

            assertThat(jmx).contains("/proposals/${proposalId}");
            assertThat(jmx).contains("${view}");
        }

        @Test
        @DisplayName("a specification with no datasets renders no CSVDataSet at all")
        void rendersNothingWithoutDatasets() {
            assertThat(renderDefault(JmeterFixtures.twoStagesThreeScenarios()))
                    .doesNotContain("CSVDataSet");
        }
    }

    /**
     * The open model, which vanilla JMeter cannot express at all.
     *
     * <p>Requests arrive at a stated rate whatever the target does with them, so throughput is an
     * input rather than an output — and a stalled target accumulates work until something stops it.
     * That something is the concurrency limit, which is why these tests care about it more than
     * about the rate.
     */
    @Nested
    @DisplayName("an arrival-rate workload")
    class ArrivalRate {

        private String renderRateProfile() {
            var specification = JmeterFixtures.specification(List.of(
                    JmeterFixtures.arrivalRateWorkload("quotes-rate", 250, List.of(
                                    new PerformanceTestSpecification.LoadStage(
                                            java.time.Duration.ofSeconds(60), 0, 100),
                                    new PerformanceTestSpecification.LoadStage(
                                            java.time.Duration.ofSeconds(300), 0, 400),
                                    new PerformanceTestSpecification.LoadStage(
                                            java.time.Duration.ofSeconds(30), 0, 0)),
                            List.of(new PerformanceTestSpecification.ScenarioSpec(
                                    "request-quote", 100, "requestQuote")))));
            return renderDefault(specification);
        }

        @Test
        @DisplayName("it renders an arrivals thread group, never a concurrency one")
        void rendersAnArrivalsGroup() {
            String jmx = renderRateProfile();

            assertThat(jmx).contains(
                    "testclass=\"com.blazemeter.jmeter.threads.arrivals.FreeFormArrivalsThreadGroup\"");
            assertThat(jmx).doesNotContain("kg.apc.jmeter.threads.UltimateThreadGroup");
        }

        @Test
        @DisplayName("each stage becomes a schedule row gliding from the previous rate")
        void stagesBecomeScheduleRows() {
            String jmx = renderRateProfile();

            // 0→100 over a minute, 100→400 over five, 400→0 over thirty seconds. The rows map
            // one-to-one onto the stages, which is why this group is used rather than the
            // fixed-shape arrivals group: nothing is approximated and nothing needs explaining.
            assertThat(scheduleIn(jmx)).containsExactly(
                    List.of(0L, 100L, 60L),
                    List.of(100L, 400L, 300L),
                    List.of(400L, 0L, 30L));
        }

        /**
         * The one field an open model cannot run without. Without it a target that stalls makes
         * arrivals accumulate until the generator exhausts its threads or its heap — and the run
         * reports the generator's death as the target's failure.
         */
        @Test
        @DisplayName("the concurrency ceiling reaches the plan")
        void ceilingReachesThePlan() {
            assertThat(renderRateProfile())
                    .contains("<stringProp name=\"ConcurrencyLimit\">250</stringProp>");
        }

        @Test
        @DisplayName("a workload with no ceiling is refused rather than rendered unbounded")
        void refusesToRenderWithoutACeiling() {
            var specification = JmeterFixtures.specification(List.of(
                    JmeterFixtures.arrivalRateWorkload("quotes-rate", 0, List.of(
                                    new PerformanceTestSpecification.LoadStage(
                                            java.time.Duration.ofSeconds(60), 0, 100)),
                            List.of(new PerformanceTestSpecification.ScenarioSpec(
                                    "request-quote", 100, "requestQuote")))));

            org.assertj.core.api.Assertions
                    .assertThatThrownBy(() -> renderDefault(specification))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxConcurrency");
        }

        @Test
        @DisplayName("both the rate and the ceiling are divided across shards")
        void ratesAreSharded() {
            var specification = JmeterFixtures.specification(List.of(
                    JmeterFixtures.arrivalRateWorkload("quotes-rate", 250, List.of(
                                    new PerformanceTestSpecification.LoadStage(
                                            java.time.Duration.ofSeconds(60), 0, 400)),
                            List.of(new PerformanceTestSpecification.ScenarioSpec(
                                    "request-quote", 100, "requestQuote")))));

            String jmx = render(specification, JmeterFixtures.shard(0, 4));

            // A quarter each, so the shards together send what the document asked for. Rounding
            // every shard up would send more traffic than the specification describes; rounding
            // every shard down would quietly send less, and the run would report a rate it never
            // achieved as though the target could not keep up.
            assertThat(scheduleIn(jmx)).containsExactly(List.of(0L, 100L, 60L));
            assertThat(jmx).contains("<stringProp name=\"ConcurrencyLimit\">63</stringProp>");
        }
    }

    /**
     * The Free-Form Arrivals schedule rows, as (start rate, end rate, duration).
     */
    private static List<List<Long>> scheduleIn(String jmx) {
        java.util.regex.Matcher rows = java.util.regex.Pattern
                .compile("<collectionProp name=\"stage-\\d+\">(.*?)</collectionProp>",
                        java.util.regex.Pattern.DOTALL)
                .matcher(jmx);

        List<List<Long>> schedule = new java.util.ArrayList<>();
        while (rows.find()) {
            java.util.regex.Matcher values = java.util.regex.Pattern
                    .compile("<stringProp name=\"\\d\">(\\d+)</stringProp>")
                    .matcher(rows.group(1));
            List<Long> row = new java.util.ArrayList<>();
            while (values.find()) {
                row.add(Long.parseLong(values.group(1)));
            }
            schedule.add(List.copyOf(row));
        }
        return List.copyOf(schedule);
    }

    /**
     * The Ultimate Thread Group's rows, as (threads, delay, ramp, hold, shutdown).
     *
     * <p>Parsed rather than matched as text because the writer indents, and an assertion that also
     * pinned the indentation would fail for a formatting change that means nothing.
     */
    private static List<List<Long>> layersIn(String jmx) {
        java.util.regex.Matcher rows = java.util.regex.Pattern
                .compile("<collectionProp name=\"layer-\\d+\">(.*?)</collectionProp>",
                        java.util.regex.Pattern.DOTALL)
                .matcher(jmx);

        List<List<Long>> layers = new java.util.ArrayList<>();
        while (rows.find()) {
            java.util.regex.Matcher values = java.util.regex.Pattern
                    .compile("<stringProp name=\"\\d\">(\\d+)</stringProp>")
                    .matcher(rows.group(1));
            List<Long> layer = new java.util.ArrayList<>();
            while (values.find()) {
                layer.add(Long.parseLong(values.group(1)));
            }
            layers.add(List.copyOf(layer));
        }
        return List.copyOf(layers);
    }

    private static int countOf(String haystack, String needle) {
        int count = 0;
        int at = haystack.indexOf(needle);
        while (at >= 0) {
            count++;
            at = haystack.indexOf(needle, at + needle.length());
        }
        return count;
    }
}
