package com.perfplatform.engine.jmeter;

import com.perfplatform.domain.plan.ExecutionPlan;
import com.perfplatform.domain.spec.PerformanceTestSpecification;
import com.perfplatform.engine.spi.RenderedWorkload;
import com.perfplatform.engine.spi.ShardAssignment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

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

            assertThat(jmx).contains("<stringProp name=\"ThreadGroup.num_threads\">1000</stringProp>");
            assertThat(jmx).doesNotContain("1,000");
        }

        @Test
        @DisplayName("a duration of an hour renders without digit grouping")
        void longDurationsRenderPlainly() {
            String jmx = renderDefault(JmeterFixtures.atMaximumConcurrency());

            // 3600, the value V1 rendered as "3,600" — its documented default endurance duration.
            assertThat(jmx).contains("<stringProp name=\"ThreadGroup.duration\">3600</stringProp>");
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

    @Nested
    @DisplayName("workload stages")
    class Stages {

        @Test
        @DisplayName("each stage becomes its own thread group")
        void oneThreadGroupPerStage() {
            String jmx = renderDefault(JmeterFixtures.twoStagesThreeScenarios());

            assertThat(jmx).contains("api-load / stage 1").contains("api-load / stage 2");
            assertThat(countOf(jmx, "testclass=\"ThreadGroup\"")).isEqualTo(2);
        }

        @Test
        @DisplayName("a later stage is delayed by the stages before it, so they do not overlap")
        void stagesAreSequenced() {
            String jmx = renderDefault(JmeterFixtures.twoStagesThreeScenarios());

            // Stage 1 starts immediately; stage 2 after stage 1's five minutes.
            assertThat(jmx).contains("<stringProp name=\"ThreadGroup.delay\">0</stringProp>");
            assertThat(jmx).contains("<stringProp name=\"ThreadGroup.delay\">300</stringProp>");
        }

        @Test
        @DisplayName("each stage carries its own target concurrency")
        void stagesCarryTheirOwnConcurrency() {
            String jmx = renderDefault(JmeterFixtures.twoStagesThreeScenarios());

            assertThat(jmx).contains("<stringProp name=\"ThreadGroup.num_threads\">100</stringProp>");
            assertThat(jmx).contains("<stringProp name=\"ThreadGroup.num_threads\">200</stringProp>");
        }

        /**
         * A stage targeting zero users expresses ramp-down. Emitting a thread group with no threads
         * would produce a group JMeter starts and immediately ends.
         */
        @Test
        @DisplayName("a ramp-down stage produces no thread group")
        void rampDownStageIsNotAThreadGroup() {
            PerformanceTestSpecification withRampDown = JmeterFixtures.specification(
                    java.util.List.of(JmeterFixtures.workload("api-load",
                            java.util.List.of(
                                    new PerformanceTestSpecification.LoadStage(
                                            java.time.Duration.ofMinutes(5), 100),
                                    new PerformanceTestSpecification.LoadStage(
                                            java.time.Duration.ofMinutes(2), 0)),
                            java.util.List.of(new PerformanceTestSpecification.ScenarioSpec(
                                    "search-client", 100, "searchClient")))));

            assertThat(countOf(renderDefault(withRampDown), "testclass=\"ThreadGroup\"")).isEqualTo(1);
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
