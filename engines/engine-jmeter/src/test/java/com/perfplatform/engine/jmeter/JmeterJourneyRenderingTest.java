package com.perfplatform.engine.jmeter;

import com.perfplatform.domain.plan.ExecutionPlan;
import com.perfplatform.domain.spec.PerformanceTestSpecification;
import com.perfplatform.domain.spec.PerformanceTestSpecification.AssertionSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.ExtractionSource;
import com.perfplatform.domain.spec.PerformanceTestSpecification.ExtractionSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.LoadStage;
import com.perfplatform.domain.spec.PerformanceTestSpecification.ScenarioSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.StepSpec;
import com.perfplatform.engine.spi.RenderedWorkload;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rendering a journey: ordered requests, values carried between them, and the guard that makes a
 * broken correlation impossible to miss.
 *
 * <p>The guard is the reason this class exists. Everything else here is mechanical; the sentinel is
 * the difference between a failed correlation that reports itself and one that produces a beautiful
 * latency curve for a system that answered 401 to everything.
 */
class JmeterJourneyRenderingTest {


    /** A sign-in journey: post credentials, keep the session, use it. */
    private static ScenarioSpec signInJourney() {
        return new ScenarioSpec("place-order", 100, null, null, null, Map.of(), Map.of(), null,
                List.of(
                        new StepSpec("sign-in", "POST", "/auth/login", Map.of(), Map.of(),
                                new ScenarioSpec.RequestBody("application/json", "{\"u\":\"a\"}"),
                                List.of(new ExtractionSpec("session", ExtractionSource.JSON_BODY,
                                        "$.token")),
                                List.of(AssertionSpec.status(200)),
                                Duration.ofSeconds(2)),
                        new StepSpec("create-order", "POST", "/api/orders",
                                Map.of("Cookie", "session=${session}"), Map.of(),
                                new ScenarioSpec.RequestBody("application/json", "{}"),
                                List.of(), List.of(AssertionSpec.status(201)), null)));
    }

    private static String render(ScenarioSpec scenario) {
        PerformanceTestSpecification specification = JmeterFixtures.specification(List.of(
                JmeterFixtures.workload("checkout",
                        List.of(new LoadStage(Duration.ofSeconds(30), 5)),
                        List.of(scenario))));
        ExecutionPlan plan = JmeterFixtures.planFor(specification);
        RenderedWorkload rendered = JmeterWorkloadRenderer.render(
                specification, plan, JmeterFixtures.shard(0, 1), JmeterFixtures.TARGET);
        return new String(rendered.bytes(), java.nio.charset.StandardCharsets.UTF_8);
    }

    @Nested
    class Ordering {

        @Test
        void everyStepBecomesItsOwnRequest() {
            String jmx = render(signInJourney());

            assertThat(jmx).contains("/auth/login").contains("/api/orders");
        }

        @Test
        void stepsRenderInTheOrderTheyWereWritten() {
            // A journey is a sequence. Rendering it out of order would send the order before the
            // sign-in that produces the session it needs.
            String jmx = render(signInJourney());

            assertThat(jmx.indexOf("/auth/login")).isLessThan(jmx.indexOf("/api/orders"));
        }

        @Test
        void aStepIsLabelledWorkloadScenarioStep() {
            String jmx = render(signInJourney());

            assertThat(jmx).contains("checkout/place-order/sign-in")
                    .contains("checkout/place-order/create-order");
        }

        @Test
        void aSingleRequestScenarioKeepsTheLabelItAlwaysHad() {
            // Objectives are scoped by these labels. Renaming them would silently re-scope every
            // objective in every stored test.
            String jmx = render(new ScenarioSpec("browse", 100, null, "GET", "/api/users"));

            assertThat(jmx).contains("checkout/browse");
            assertThat(jmx).doesNotContain("checkout/browse/browse");
        }
    }

    @Nested
    class Correlation {

        @Test
        void aJsonExtractionBecomesAPostProcessor() {
            String jmx = render(signInJourney());

            assertThat(jmx).contains("JSONPostProcessor")
                    .contains("<stringProp name=\"JSONPostProcessor.referenceNames\">session</stringProp>")
                    .contains("$.token");
        }

        @Test
        void anExtractedValuePassesThroughToTheStepThatUsesIt() {
            String jmx = render(signInJourney());

            assertThat(jmx).contains("session=${session}");
        }

        @Test
        void everyExtractionCarriesASentinelDefault() {
            // The whole point. Without a default JMeter substitutes an empty string, and an empty
            // Cookie header looks like a request somebody meant to send.
            String jmx = render(signInJourney());

            assertThat(jmx).contains("PERF_NOT_EXTRACTED_session");
        }

        @Test
        void aRequiredExtractionAlsoRendersTheAssertionThatFailsOnTheSentinel() {
            // This is what turns a silent failure into a visible one. Without it, a non-matching
            // extraction sends every later request with unfilled text, the target answers 4xx in
            // milliseconds, and the run reports its best latency ever for work it never did.
            String jmx = render(signInJourney());

            assertThat(jmx).contains("<stringProp name=\"Assertion.scope\">variable</stringProp>")
                    .contains("<stringProp name=\"Scope.variable\">session</stringProp>")
                    // 8 (equals) + 4 (not): the sample fails when the variable IS the sentinel.
                    .contains("<intProp name=\"Assertion.test_type\">12</intProp>");
        }

        @Test
        void anOptionalExtractionKeepsItsSentinelButNotItsAssertion() {
            ScenarioSpec optional = new ScenarioSpec("j", 100, null, null, null, Map.of(), Map.of(),
                    null,
                    List.of(new StepSpec("read", "GET", "/api/thing", Map.of(), Map.of(), null,
                            List.of(new ExtractionSpec("maybe", ExtractionSource.JSON_BODY,
                                    "$.maybe", false)),
                            List.of(), null)));

            String jmx = render(optional);

            assertThat(jmx).contains("PERF_NOT_EXTRACTED_maybe");
            assertThat(jmx).doesNotContain("<stringProp name=\"Scope.variable\">maybe</stringProp>");
        }

        @Test
        void aCookieExtractionReadsTheHeaderBlock() {
            ScenarioSpec cookie = new ScenarioSpec("j", 100, null, null, null, Map.of(), Map.of(),
                    null,
                    List.of(new StepSpec("in", "POST", "/auth", Map.of(), Map.of(), null,
                            List.of(new ExtractionSpec("jsid", ExtractionSource.SET_COOKIE,
                                    "JSESSIONID")),
                            List.of(), null)));

            String jmx = render(cookie);

            assertThat(jmx).contains("RegexExtractor").contains("response_headers");
        }
    }

    @Nested
    class Expectations {

        @Test
        void aStatusExpectationChecksTheResponseCode() {
            String jmx = render(signInJourney());

            assertThat(jmx).contains("<stringProp name=\"Assertion.test_field\">Assertion.response_code</stringProp>")
                    .contains("<stringProp name=\"0\">200</stringProp>")
                    .contains("<stringProp name=\"0\">201</stringProp>");
        }

        @Test
        void assertionsAreNotAssumedSuccessful() {
            // assume_success would record the sample as passing whatever the assertion found, which
            // is the opposite of the point.
            String jmx = render(signInJourney());

            assertThat(jmx).doesNotContain("<boolProp name=\"Assertion.assume_success\">true</boolProp>");
        }
    }

    @Nested
    class ThinkTime {

        @Test
        void aPauseBecomesARandomisedTimer() {
            // Fixed pauses synchronise every virtual user into waves no real population makes.
            String jmx = render(signInJourney());

            assertThat(jmx).contains("UniformRandomTimer")
                    .contains("<stringProp name=\"RandomTimer.range\">");
        }

        @Test
        void aStepWithNoThinkTimeRendersNoTimer() {
            String jmx = render(new ScenarioSpec("browse", 100, null, "GET", "/api/users"));

            assertThat(jmx).doesNotContain("UniformRandomTimer");
        }
    }
}
