package com.perfplatform.domain.validation;

import com.perfplatform.domain.spec.PerformanceTestSpecification;
import com.perfplatform.domain.spec.PerformanceTestSpecification.AssertionSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.ExtractionSource;
import com.perfplatform.domain.spec.PerformanceTestSpecification.ExtractionSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.ScenarioSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.StepSpec;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rules that make a journey safe to run.
 *
 * <p>The first class of them exists because <b>steps were a new way to write a request, and a new
 * way to write a request is a new way to smuggle a credential past a guard that only knew about the
 * old one.</b> Every check a single-request scenario gets, a step gets.
 *
 * <p>These were briefly dead code: an early return for journeys sat above the call that ran them, so
 * a journey validated clean no matter what it contained. Found by validating a document that should
 * have been refused and watching it pass. Several of the tests below would have caught it.
 */
class JourneyValidationTest {

    private static List<ValidationFinding> validate(ScenarioSpec scenario) {
        return SemanticValidator.validate(SpecificationFixturesForJourneys.with(scenario)).findings();
    }

    private static boolean hasError(List<ValidationFinding> findings, String code) {
        return findings.stream()
                .anyMatch(f -> f.code().equals(code) && f.severity() == Severity.ERROR);
    }

    private static ScenarioSpec journey(StepSpec... steps) {
        return new ScenarioSpec("journey", 100, null, null, null, Map.of(), Map.of(), null,
                List.of(steps));
    }

    @Nested
    class CredentialsCannotHideInAStep {

        @Test
        void anAuthorizationHeaderOnAStepIsRefusedByName() {
            var findings = validate(journey(new StepSpec("in", "POST", "/auth",
                    Map.of("Authorization", "Bearer abc"), Map.of(), null,
                    List.of(), List.of(), null)));

            assertThat(hasError(findings, "spec.scenario.header.credential")).isTrue();
        }

        @Test
        void aTokenShapedValueUnderAnyHeaderNameIsRefused() {
            var findings = validate(journey(new StepSpec("in", "POST", "/auth",
                    Map.of("X-Custom", "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.abcdefghijklmnop"),
                    Map.of(), null, List.of(), List.of(), null)));

            assertThat(findings).anyMatch(f -> f.severity() == Severity.ERROR);
        }

        @Test
        void aCredentialInAStepQueryParameterIsRefused() {
            // A query string is written to every access log between the runner and the target, so
            // this matters more than the header case rather than less.
            var findings = validate(journey(new StepSpec("in", "GET", "/api",
                    Map.of(), Map.of("x-api-key", "whatever"), null,
                    List.of(), List.of(), null)));

            assertThat(hasError(findings, "spec.scenario.query.inline-secret")).isTrue();
        }

        @Test
        void aCredentialHeaderMayCarryAReferenceToSomethingTheRunSupplies() {
            // The guard stops a SECRET being stored. 'Bearer ${session}' stores no secret -- it is
            // a placeholder for a value extracted during the run. Refusing it would make
            // correlation useless for the case it exists to serve.
            var findings = validate(journey(
                    new StepSpec("in", "POST", "/auth", Map.of(), Map.of(), null,
                            List.of(new ExtractionSpec("session", ExtractionSource.JSON_BODY, "$.t")),
                            List.of(), null),
                    new StepSpec("use", "GET", "/api",
                            Map.of("Authorization", "Bearer ${session}"), Map.of(), null,
                            List.of(), List.of(), null)));

            assertThat(findings).noneMatch(f -> f.severity() == Severity.ERROR);
        }

        @Test
        void aReferenceDoesNotLaunderASecretBesideIt() {
            var findings = validate(journey(
                    new StepSpec("in", "POST", "/auth", Map.of(), Map.of(), null,
                            List.of(new ExtractionSpec("session", ExtractionSource.JSON_BODY, "$.t")),
                            List.of(), null),
                    new StepSpec("use", "GET", "/api",
                            Map.of("Authorization",
                                    "Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.abcdefghij ${session}"),
                            Map.of(), null, List.of(), List.of(), null)));

            assertThat(findings).anyMatch(f -> f.severity() == Severity.ERROR);
        }

        @Test
        void aCredentialHeaderReferencingSomethingNobodyProvidesIsStillRefused() {
            var findings = validate(journey(new StepSpec("use", "GET", "/api",
                    Map.of("Authorization", "Bearer ${nothing}"), Map.of(), null,
                    List.of(), List.of(), null)));

            assertThat(findings).anyMatch(f -> f.severity() == Severity.ERROR);
        }

        @Test
        void noFindingEchoesTheValueItRefused() {
            String secret = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.abcdefghijklmnop";
            var findings = validate(journey(new StepSpec("in", "POST", "/auth",
                    Map.of("X-Custom", secret), Map.of(), null, List.of(), List.of(), null)));

            for (ValidationFinding finding : findings) {
                assertThat(finding.message()).doesNotContain(secret);
                assertThat(String.valueOf(finding.remediation())).doesNotContain(secret);
            }
        }
    }

    @Nested
    class ReferencesMustAlreadyExist {

        @Test
        void aStepMayUseWhatAnEarlierStepExtracted() {
            var findings = validate(journey(
                    new StepSpec("in", "POST", "/auth", Map.of(), Map.of(), null,
                            List.of(new ExtractionSpec("session", ExtractionSource.JSON_BODY, "$.t")),
                            List.of(), null),
                    new StepSpec("use", "GET", "/api", Map.of("Cookie", "s=${session}"), Map.of(),
                            null, List.of(), List.of(), null)));

            assertThat(hasError(findings, "spec.step.reference.unresolved")).isFalse();
        }

        @Test
        void aStepMayNotUseWhatOnlyALaterStepExtracts() {
            // Not a timing subtlety. The request goes out with the characters ${session} in it.
            var findings = validate(journey(
                    new StepSpec("use", "GET", "/api", Map.of("Cookie", "s=${session}"), Map.of(),
                            null, List.of(), List.of(), null),
                    new StepSpec("in", "POST", "/auth", Map.of(), Map.of(), null,
                            List.of(new ExtractionSpec("session", ExtractionSource.JSON_BODY, "$.t")),
                            List.of(), null)));

            assertThat(hasError(findings, "spec.step.reference.unresolved")).isTrue();
        }

        @Test
        void aReferenceNothingProvidesIsRefused() {
            var findings = validate(journey(new StepSpec("use", "GET", "/api/${nothing}")));

            assertThat(hasError(findings, "spec.step.reference.unresolved")).isTrue();
        }
    }

    @Nested
    class ExtractionNames {

        @Test
        void twoStepsMayNotExtractTheSameName() {
            var findings = validate(journey(
                    new StepSpec("a", "GET", "/1", Map.of(), Map.of(), null,
                            List.of(new ExtractionSpec("id", ExtractionSource.JSON_BODY, "$.id")),
                            List.of(), null),
                    new StepSpec("b", "GET", "/2", Map.of(), Map.of(), null,
                            List.of(new ExtractionSpec("id", ExtractionSource.JSON_BODY, "$.id")),
                            List.of(), null)));

            assertThat(hasError(findings, "spec.extract.name.duplicate")).isTrue();
        }

        @Test
        void anExtractionNeedsAnExpressionUnlessItReadsTheStatus() {
            var missing = validate(journey(new StepSpec("a", "GET", "/1", Map.of(), Map.of(), null,
                    List.of(new ExtractionSpec("v", ExtractionSource.JSON_BODY, null)),
                    List.of(), null)));
            assertThat(hasError(missing, "spec.extract.expression.missing")).isTrue();

            var status = validate(journey(new StepSpec("a", "GET", "/1", Map.of(), Map.of(), null,
                    List.of(new ExtractionSpec("code", ExtractionSource.STATUS, null)),
                    List.of(), null)));
            assertThat(hasError(status, "spec.extract.expression.missing")).isFalse();
        }

        @Test
        void aBodyPatternMustCaptureExactlyOneGroup() {
            // Zero captures nothing; two is ambiguous and the engine would silently pick one.
            var none = validate(journey(new StepSpec("a", "GET", "/1", Map.of(), Map.of(), null,
                    List.of(new ExtractionSpec("v", ExtractionSource.REGEX_BODY, "token=.+")),
                    List.of(), null)));
            assertThat(hasError(none, "spec.extract.expression.groups")).isTrue();

            var two = validate(journey(new StepSpec("a", "GET", "/1", Map.of(), Map.of(), null,
                    List.of(new ExtractionSpec("v", ExtractionSource.REGEX_BODY, "(a)=(b)")),
                    List.of(), null)));
            assertThat(hasError(two, "spec.extract.expression.groups")).isTrue();

            var one = validate(journey(new StepSpec("a", "GET", "/1", Map.of(), Map.of(), null,
                    List.of(new ExtractionSpec("v", ExtractionSource.REGEX_BODY, "token=(.+)")),
                    List.of(), null)));
            assertThat(hasError(one, "spec.extract.expression.groups")).isFalse();
        }

        @Test
        void anUncompilablePatternIsReportedRatherThanThrown() {
            var findings = validate(journey(new StepSpec("a", "GET", "/1", Map.of(), Map.of(), null,
                    List.of(new ExtractionSpec("v", ExtractionSource.REGEX_BODY, "([unclosed")),
                    List.of(), null)));

            assertThat(hasError(findings, "spec.extract.expression.invalid")).isTrue();
        }
    }

    @Nested
    class Shape {

        @Test
        void aStepNeedsAPath() {
            var findings = validate(journey(new StepSpec("a", "GET", null)));

            assertThat(hasError(findings, "spec.step.path.missing")).isTrue();
        }

        @Test
        void aStepPathMayNotBeAFullUrl() {
            // A specification cannot name its own host. That is the whole target policy.
            var findings = validate(journey(new StepSpec("a", "GET", "https://elsewhere/api")));

            assertThat(findings).anyMatch(f -> f.severity() == Severity.ERROR);
        }

        @Test
        void statingBothASingleRequestAndStepsIsRefused() {
            var scenario = new ScenarioSpec("j", 100, null, "GET", "/api/direct",
                    Map.of(), Map.of(), null, List.of(new StepSpec("a", "GET", "/api/step")));

            assertThat(hasError(validate(scenario), "spec.scenario.request.ambiguous")).isTrue();
        }

        @Test
        void anExpectationMustNameAPlausibleStatusCode() {
            var findings = validate(journey(new StepSpec("a", "GET", "/1", Map.of(), Map.of(), null,
                    List.of(),
                    List.of(new AssertionSpec(PerformanceTestSpecification.AssertionSubject.STATUS,
                            PerformanceTestSpecification.AssertionOperator.EQUALS, "twenty")),
                    null)));

            assertThat(hasError(findings, "spec.expect.status.invalid")).isTrue();
        }

        @Test
        void aWellFormedJourneyProducesNoErrors() {
            var findings = validate(journey(
                    new StepSpec("in", "POST", "/auth", Map.of(), Map.of(), null,
                            List.of(new ExtractionSpec("session", ExtractionSource.JSON_BODY, "$.t")),
                            List.of(AssertionSpec.status(200)), java.time.Duration.ofSeconds(2)),
                    new StepSpec("use", "GET", "/api",
                            Map.of("Authorization", "Bearer ${session}"), Map.of(),
                            null, List.of(), List.of(AssertionSpec.status(200)), null)));

            assertThat(findings).noneMatch(f -> f.severity() == Severity.ERROR);
        }
    }
}
