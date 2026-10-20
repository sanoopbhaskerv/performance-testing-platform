package com.perfplatform.api.v2.spec;

import com.perfplatform.domain.spec.PerformanceTestSpecification;
import com.perfplatform.domain.spec.SourceType;
import com.perfplatform.domain.spec.TargetClass;
import com.perfplatform.domain.spec.WorkloadType;
import com.perfplatform.domain.validation.Severity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises parsing and validation against the specification examples that ship in
 * {@code specification/examples/}.
 */
class SpecificationValidationServiceTest {

    /**
     * The engines a test deployment has.
     *
     * <p>Both, because the shipped examples use both and a validator that reported the browser
     * engine as unavailable would fail every browser fixture for the wrong reason.
     */
    private static final com.perfplatform.domain.port.EngineCatalog ENGINES =
            () -> java.util.Set.of("jmeter", "playwright");

    private final SpecificationValidationService service =
            new SpecificationValidationService(new SpecificationParser(), ENGINES);

    @Test
    void configuredExecutionLimitsRejectBothDocumentAndMappedSpecifications() {
        var restricted = new SpecificationValidationService(new SpecificationParser(), ENGINES,
                com.perfplatform.domain.spec.GitSourceAllowlist.none(),
                new com.perfplatform.domain.admission.ExecutionLimits(10, Duration.ofSeconds(30)));
        var outcome = restricted.validate(ExampleFixtures.valid("api-100-rps.yaml"));
        assertThat(outcome.findings().codes()).contains("policy.protocolUsers.exceeded", "policy.duration.exceeded");
        assertThat(outcome.digest()).isEmpty();
        assertThat(restricted.validateSpecification(outcome.specification().orElseThrow()).findings().codes())
                .contains("policy.protocolUsers.exceeded", "policy.duration.exceeded");
    }

    @Nested
    @DisplayName("published valid examples")
    class ValidExamples {

        @ParameterizedTest(name = "{0} parses and validates")
        @ValueSource(strings = {
                "api-only.yaml", "browser-only.yaml", "hybrid.yaml", "demo-service.yaml", "api-100-rps.yaml",
                "session-broker-api.yaml", "arrival-rate.yaml"})
        @DisplayName("every shipped valid example is valid")
        void validExamplesValidate(String name) {
            var outcome = service.validate(ExampleFixtures.valid(name));

            assertThat(outcome.findings().errors())
                    .as("errors in %s: %s", name, outcome.findings().errors())
                    .isEmpty();
            assertThat(outcome.isValid()).isTrue();
            assertThat(outcome.digest()).isPresent();
        }

        @Test
        @DisplayName("the API example maps onto the domain model faithfully")
        void mapsApiExample() {
            var outcome = service.validate(ExampleFixtures.valid("api-only.yaml"));
            PerformanceTestSpecification spec = outcome.specification().orElseThrow();

            assertThat(spec.apiVersion()).isEqualTo(PerformanceTestSpecification.SUPPORTED_API_VERSION);
            assertThat(spec.metadata().team()).isEqualTo("advice-implementation");
            assertThat(spec.metadata().labels()).containsEntry("tier", "tier-0");
            assertThat(spec.spec().environment().targetClass()).isEqualTo(TargetClass.NON_PRODUCTION);
            assertThat(spec.spec().sources().get(0).type()).isEqualTo(SourceType.OPENAPI);
            assertThat(spec.spec().datasets().get(0).partitionByShard()).isTrue();

            var workload = spec.spec().workloads().get(0);
            assertThat(workload.type()).isEqualTo(WorkloadType.PROTOCOL);
            assertThat(workload.model().stages()).hasSize(3);
            assertThat(workload.model().stages().get(0).duration()).isEqualTo(Duration.ofMinutes(5));
            assertThat(workload.peakUsers()).isEqualTo(600);
            assertThat(workload.scenarios()).extracting("name")
                    .containsExactly("search-client", "open-proposal", "save-proposal");
            assertThat(workload.scenarios()).extracting("weight").containsExactly(60, 30, 10);

            assertThat(spec.spec().objectives().get(0).threshold().canonicalForm()).isEqualTo("750ms");
            assertThat(spec.spec().governance().maxDuration()).isEqualTo(Duration.ofMinutes(45));
        }

        @Test
        @DisplayName("scenario weights and boundaries survive parsing, unlike V1")
        void preservesScenarioBoundaries() {
            var outcome = service.validate(ExampleFixtures.valid("hybrid.yaml"));
            var workload = outcome.specification().orElseThrow().spec().workloads().get(0);

            assertThat(workload.scenarios()).hasSize(3);
            assertThat(workload.scenarios().stream().mapToInt(s -> s.weight()).sum()).isEqualTo(100);
        }

        /**
         * The manual form, end to end through the parser.
         *
         * <p>This is what makes a specification able to address a real endpoint before importers
         * exist. Asserted against the shipped demo example rather than an inline stub, so the file a
         * consumer copies from is the file that is proven to work.
         */
        @Test
        @DisplayName("a scenario's own method and path survive parsing")
        void preservesManualRequests() {
            var spec = service.validate(ExampleFixtures.valid("demo-service.yaml"))
                    .specification().orElseThrow();
            var scenarios = spec.spec().workloads().get(0).scenarios();

            assertThat(scenarios).extracting(s -> s.methodOrDefault() + " " + s.path())
                    .containsExactly("GET /api/students/1", "PUT /api/students/1");
            assertThat(scenarios).allMatch(s -> s.hasExplicitRequest());
        }

        /** A scenario that names neither a path nor an operation has nothing to call. */
        @Test
        @DisplayName("a scenario with no path and no operationId is rejected")
        void scenarioMustSayWhatToCall() {
            var outcome = service.validate(scenarioDocument("weight: 100"));

            assertThat(outcome.findings().errors())
                    .extracting(f -> f.code())
                    .contains("spec.scenario.request.missing");
        }

        /**
         * A specification cannot name its own host.
         *
         * <p>Where a run's traffic goes is resolved from the team's registered targets. Accepting a
         * full URL in a scenario path would be a way around the entire target policy, so it is
         * refused at validation where the author sees it, not at render time where it would surface
         * as a failed run.
         */
        @Test
        @DisplayName("a scenario path that is a full URL is refused")
        void scenarioPathCannotChooseItsOwnHost() {
            var outcome = service.validate(
                    scenarioDocument("weight: 100\n          path: http://evil.example.com/api"));

            assertThat(outcome.findings().errors())
                    .extracting(f -> f.code())
                    .containsAnyOf("spec.scenario.path.invalid", "spec.scenario.path.absolute");
        }

        /**
         * The whole point of the secret-reference model is that a credential never enters a
         * document that is stored, rendered, committed to Git and echoed back by this endpoint.
         * A header is the most obvious way around it, so the name is refused whatever the value —
         * that check cannot produce a false negative.
         */
        @Test
        @DisplayName("an Authorization header is refused whatever its value")
        void authorizationHeaderIsRefused() {
            var outcome = service.validate(scenarioDocument("""
                    weight: 100
                              path: /api/users
                              headers:
                                Authorization: "Bearer not-a-real-token"\
                    """));

            assertThat(outcome.findings().errors())
                    .extracting(f -> f.code())
                    .contains("spec.scenario.header.credential");
        }

        @Test
        @DisplayName("Cookie and X-Api-Key are refused too")
        void otherCredentialHeadersAreRefused() {
            for (String header : java.util.List.of("Cookie", "X-Api-Key", "Proxy-Authorization")) {
                var outcome = service.validate(scenarioDocument("""
                        weight: 100
                                  path: /api/users
                                  headers:
                                    %s: something\
                        """.formatted(header)));

                assertThat(outcome.findings().errors())
                        .as("header %s", header)
                        .extracting(f -> f.code())
                        .contains("spec.scenario.header.credential");
            }
        }

        /** A token written under a name the platform does not recognise is still a token. */
        @Test
        @DisplayName("a credential-shaped value under an ordinary header name is refused")
        void tokenShapedHeaderValueIsRefused() {
            var outcome = service.validate(scenarioDocument("""
                    weight: 100
                              path: /api/users
                              headers:
                                X-Trace: "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.abc"\
                    """));

            assertThat(outcome.findings().errors())
                    .extracting(f -> f.code())
                    .contains("spec.scenario.header.inline-secret");
        }

        /**
         * A finding is logged and returned over HTTP. One that quoted the credential would spread it
         * further than the document did.
         */
        @Test
        @DisplayName("a rejected header finding never echoes the value it rejected")
        void findingNeverEchoesTheCredential() {
            String credential = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJzZWNyZXQifQ.s3cr3t";
            var outcome = service.validate(scenarioDocument("""
                    weight: 100
                              path: /api/users
                              headers:
                                X-Trace: "%s"\
                    """.formatted(credential)));

            assertThat(outcome.findings().findings())
                    .allSatisfy(finding -> assertThat(finding.message() + finding.remediation())
                            .doesNotContain(credential));
        }

        /**
         * Sharper than the header case: a query string is recorded by every proxy, load balancer and
         * access log between the runner and the target (CLAUDE.md §20).
         */
        @Test
        @DisplayName("a credential in a query parameter is refused")
        void credentialInQueryIsRefused() {
            var outcome = service.validate(scenarioDocument("""
                    weight: 100
                              path: /api/users
                              query:
                                api_key: "AKIAIOSFODNN7EXAMPLE"\
                    """));

            assertThat(outcome.findings().errors())
                    .extracting(f -> f.code())
                    .contains("spec.scenario.query.inline-secret");
        }

        /**
         * The check runs over ordinary values, so it has to leave them alone. A validator that cried
         * wolf on {@code application/json} would be worked around, and then it would protect nothing.
         */
        @Test
        @DisplayName("ordinary headers and query values are left alone")
        void ordinaryValuesAreNotFlagged() {
            var outcome = service.validate(scenarioDocument("""
                    weight: 100
                              path: /api/users
                              headers:
                                Accept: application/json
                                X-Correlation-Mode: passthrough
                              query:
                                page: "1"
                                sort: name,asc\
                    """));

            assertThat(outcome.findings().errors())
                    .as("errors: %s", outcome.findings().errors())
                    .isEmpty();
            assertThat(outcome.isValid()).isTrue();
        }

        /**
         * Many servers and proxies drop a GET body silently, so the test would measure a different
         * request than the one written — a wrong answer rather than a failure.
         */
        @Test
        @DisplayName("a body on a GET is refused")
        void bodyOnGetIsRefused() {
            var outcome = service.validate(scenarioDocument("""
                    weight: 100
                              method: GET
                              path: /api/users
                              body: '{"filter":"all"}'\
                    """));

            assertThat(outcome.findings().errors())
                    .extracting(f -> f.code())
                    .contains("spec.scenario.body.notAllowed");
        }

        @Test
        @DisplayName("a bare string body is accepted as shorthand")
        void bareStringBodyIsShorthand() {
            var outcome = service.validate(scenarioDocument("""
                    weight: 100
                              method: POST
                              path: /api/payments
                              body: '{"amount":100}'\
                    """));

            assertThat(outcome.findings().errors()).isEmpty();
            var scenario = outcome.specification().orElseThrow()
                    .spec().workloads().get(0).scenarios().get(0);
            assertThat(scenario.body().content()).isEqualTo("{\"amount\":100}");
            assertThat(scenario.body().contentTypeOrDefault()).isEqualTo("application/json");
        }

        @Test
        @DisplayName("an unsupported HTTP method is refused rather than passed to the engine")
        void unsupportedMethodIsRefused() {
            var outcome = service.validate(
                    scenarioDocument("weight: 100\n          method: TRACE\n          path: /api"));

            assertThat(outcome.findings().errors())
                    .extracting(f -> f.code())
                    .contains("spec.scenario.method.unsupported");
        }

        /** A minimal document whose single scenario is built from the given YAML lines. */
        private static String scenarioDocument(String scenarioBody) {
            return """
                    apiVersion: performance.ameriprise.com/v1alpha1
                    kind: PerformanceTest
                    metadata:
                      name: scenario-check
                      team: local-dev
                      application: demo-service
                    spec:
                      environment:
                        name: performance
                        runnerCell: local
                        targetClass: non-production
                      workloads:
                        - name: check-load
                          type: protocol
                          engine: jmeter
                          model:
                            type: stages
                            stages:
                              - duration: 1m
                                targetUsers: 5
                          scenarios:
                            - name: only
                              %s
                      objectives:
                        - metric: http.error.rate
                          scope: check-load
                          threshold: 1%%
                    """.formatted(scenarioBody);
        }

        @Test
        @DisplayName("the hybrid example separates protocol and browser budgets")
        void separatesBudgets() {
            var spec = service.validate(ExampleFixtures.valid("hybrid.yaml"))
                    .specification().orElseThrow();

            assertThat(spec.peakProtocolUsers()).isEqualTo(600);
            assertThat(spec.peakBrowserSessions()).isEqualTo(5);
            assertThat(spec.spec().workloads().get(1).startWhen().workload())
                    .isEqualTo("api-background-load");
        }

        @Test
        @DisplayName("an equivalent JSON document produces the same digest as the YAML")
        void jsonAndYamlAgree() {
            String yaml = ExampleFixtures.valid("browser-only.yaml");
            String json = """
                    {
                      "apiVersion": "performance.ameriprise.com/v1alpha1",
                      "kind": "PerformanceTest",
                      "metadata": {
                        "name": "proposal-journey",
                        "team": "advice-implementation",
                        "application": "proposal"
                      },
                      "spec": {
                        "environment": {
                          "name": "performance",
                          "runnerCell": "advice-perf-eks",
                          "targetClass": "non-production"
                        },
                        "sources": [{
                          "name": "proposal-browser",
                          "type": "playwright",
                          "uri": "artifact://playwright-sources/sha256-a9e8/proposal-journey.spec.ts",
                          "digest": "sha256:a9e8d7c6b5a4938271605f4e3d2c1b0aa0b1c2d3e4f5061728394a5b6c7d8e9f"
                        }],
                        "workloads": [{
                          "name": "browser-experience",
                          "type": "browser",
                          "engine": "playwright",
                          "sourceRef": "proposal-browser",
                          "browserUsers": 5,
                          "iterationsPerUser": 3,
                          "browserProfile": {
                            "device": "mobile",
                            "network": "slow-4g",
                            "cpuThrottle": 4
                          }
                        }],
                        "objectives": [{
                          "metric": "browser.journey.duration",
                          "scope": "browser-experience",
                          "percentile": 95,
                          "threshold": "8s"
                        }, {
                          "metric": "web.lcp",
                          "scope": "browser-experience",
                          "percentile": 95,
                          "threshold": "2500ms"
                        }, {
                          "metric": "web.cls",
                          "scope": "browser-experience",
                          "percentile": 95,
                          "threshold": "0.1score"
                        }],
                        "governance": {
                          "maxDuration": "30m",
                          "approvalPolicy": "non-production-standard",
                          "retention": "90d"
                        }
                      }
                    }
                    """;

            assertThat(service.validate(json).digest())
                    .isEqualTo(service.validate(yaml).digest());
        }
    }

    /**
     * Where a Playwright test may be fetched from.
     *
     * <p>Deployment policy, not document shape — the same specification is legitimately valid in a
     * deployment that allowlists the repository and invalid in one that does not. What must never
     * happen is the third case: accepted, and then silently fetched from anywhere.
     */
    @Nested
    @DisplayName("git-pinned Playwright sources")
    class GitPinnedSources {

        private static final String ALLOWED = "ssh://bitbucket/proposal-e2e";

        private SpecificationValidationService withAllowlist(String... repositories) {
            return new SpecificationValidationService(new SpecificationParser(), ENGINES,
                    com.perfplatform.domain.spec.GitSourceAllowlist.of(List.of(repositories)));
        }

        /**
         * The shipped example. It is checked here rather than in {@code ValidExamples} because it
         * is the one valid example whose validity depends on deployment configuration — under the
         * default allowlist it is correctly refused, and asserting it there would have to either
         * weaken the default or pretend the policy does not exist.
         */
        @Test
        @DisplayName("the shipped browser-git-pinned example validates against its repository")
        void shippedExampleValidates() {
            var outcome = withAllowlist("ssh://bitbucket/proposal-e2e")
                    .validate(ExampleFixtures.valid("browser-git-pinned.yaml"));

            assertThat(outcome.findings().errors())
                    .as("errors: %s", outcome.findings().errors())
                    .isEmpty();
            assertThat(outcome.digest()).isPresent();
        }

        @Test
        @DisplayName("an allowlisted repository at an immutable commit validates")
        void allowlistedRepositoryValidates() {
            var outcome = withAllowlist(ALLOWED).validate(document("a".repeat(40)));

            assertThat(outcome.findings().codes())
                    .doesNotContain("spec.source.git.repository.notAllowed",
                            "spec.source.git.repository.noneAllowed");
            assertThat(outcome.isValid()).isTrue();
        }

        @Test
        @DisplayName("a repository this deployment does not allowlist is refused")
        void unlistedRepositoryIsRefused() {
            var outcome = withAllowlist("ssh://bitbucket/something-else")
                    .validate(document("a".repeat(40)));

            assertThat(outcome.findings().codes())
                    .contains("spec.source.git.repository.notAllowed");
            assertThat(outcome.isValid()).isFalse();
            assertThat(outcome.digest())
                    .as("a refused specification must not be given a digest")
                    .isEmpty();
        }

        @Test
        @DisplayName("the default deployment allowlists nothing, so git-pinned imports are refused")
        void defaultDeploymentRefuses() {
            var outcome = service.validate(document("a".repeat(40)));

            assertThat(outcome.findings().codes())
                    .contains("spec.source.git.repository.noneAllowed");
        }

        /** Shape and policy are separate layers, and both findings should arrive together. */
        @Test
        @DisplayName("a mutable ref on an allowlisted repository still fails on the ref")
        void allowlistDoesNotExcuseAMutableRef() {
            var outcome = withAllowlist(ALLOWED).validate(document("main"));

            assertThat(outcome.findings().codes()).contains("spec.source.git.ref.mutable");
            assertThat(outcome.isValid()).isFalse();
        }

        private String document(String ref) {
            return """
                    apiVersion: performance.ameriprise.com/v1alpha1
                    kind: PerformanceTest
                    metadata:
                      name: mutable-ref
                      team: advice-implementation
                      application: proposal
                    spec:
                      environment:
                        name: performance
                        runnerCell: advice-perf-eks
                        targetClass: non-production
                      sources:
                        - name: proposal-browser
                          type: playwright
                          git:
                            repository: ssh://bitbucket/proposal-e2e.git
                            ref: REF
                            path: tests/performance/proposal-journey.spec.ts
                      workloads:
                        - name: browser-experience
                          type: browser
                          engine: playwright
                          sourceRef: proposal-browser
                          browserUsers: 5
                      objectives:
                        - metric: browser.journey.duration
                          percentile: 95
                          threshold: 8s
                    """.replace("REF", ref);
        }
    }

    @Nested
    @DisplayName("published invalid examples")
    class InvalidExamples {

        @ParameterizedTest(name = "{0} produces {1}")
        @CsvSource({
                "inline-secret.yaml, spec.secret.inline-value",
                "mutable-git-ref.yaml, spec.source.git.ref.mutable",
                "no-workloads.yaml, spec.workload.missing",
                "unresolved-references.yaml, spec.workload.sourceRef.unresolved",
                "unitless-threshold.yaml, spec.parse.threshold.invalid",
                "duration-exceeds-governance.yaml, spec.governance.maxDuration.exceeded",
                "unsupported-engine.yaml, spec.parse.enum.invalid",
                "arrival-rate-without-ceiling.yaml, spec.workload.model.maxConcurrency.missing",
                "stages-with-rate.yaml, spec.workload.stage.rate.notApplicable"
        })
        @DisplayName("each invalid example produces its documented stable finding code")
        void invalidExamplesProduceStableCodes(String name, String expectedCode) {
            var outcome = service.validate(ExampleFixtures.invalid(name));

            assertThat(outcome.findings().codes()).contains(expectedCode);
            assertThat(outcome.isValid()).isFalse();
            assertThat(outcome.digest())
                    .as("a rejected specification must not be given a digest")
                    .isEmpty();
        }

        @Test
        @DisplayName("the unresolved-reference example reports both broken references at once")
        void reportsAllUnresolvedReferences() {
            var outcome = service.validate(ExampleFixtures.invalid("unresolved-references.yaml"));

            assertThat(outcome.findings().codes())
                    .contains("spec.workload.sourceRef.unresolved", "spec.objective.scope.unresolved");
        }

        @Test
        @DisplayName("a rejected secret finding never echoes the credential")
        void neverEchoesCredential() {
            var outcome = service.validate(ExampleFixtures.invalid("inline-secret.yaml"));

            assertThat(outcome.findings().findings())
                    .allSatisfy(finding -> assertThat(finding.message())
                            .doesNotContain("eyJhbGciOiJIUzI1NiJ9"));
        }
    }

    @Nested
    @DisplayName("structural parsing")
    class Structural {

        @Test
        @DisplayName("an empty document is reported, not thrown")
        void reportsEmptyDocument() {
            assertThat(service.validate("").findings().codes()).containsExactly("spec.parse.empty");
            assertThat(service.validate(null).findings().codes()).containsExactly("spec.parse.empty");
        }

        @Test
        @DisplayName("malformed YAML is reported without echoing document content")
        void reportsMalformedYaml() {
            var outcome = service.validate("apiVersion: [unclosed\n  kind: ]bad");

            assertThat(outcome.findings().codes()).contains("spec.parse.malformed");
            assertThat(outcome.isValid()).isFalse();
        }

        /**
         * Silently ignoring an unknown field is the dangerous option: a consumer who writes
         * {@code targetUser} would get a workload running at a load they never chose.
         */
        @Test
        @DisplayName("an unknown field is rejected rather than silently ignored")
        void rejectsUnknownField() {
            String document = ExampleFixtures.valid("api-only.yaml")
                    .replace("            targetUsers: 200", "            targetUser: 200");
            var outcome = service.validate(document);

            assertThat(outcome.findings().codes()).contains("spec.parse.unknownField");
            assertThat(outcome.isValid()).isFalse();
        }

        @Test
        @DisplayName("a wrongly typed field is reported with its path")
        void reportsTypeErrorWithPath() {
            String document = ExampleFixtures.valid("api-only.yaml")
                    .replace("            targetUsers: 200", "            targetUsers: many");
            var outcome = service.validate(document);

            assertThat(outcome.findings().findings())
                    .anyMatch(finding -> finding.code().equals("spec.parse.type.invalid")
                            && finding.path().equals("/spec/workloads/0/model/stages/0/targetUsers"));
        }

        @Test
        @DisplayName("an unparseable duration is reported with guidance")
        void reportsInvalidDuration() {
            String document = ExampleFixtures.valid("api-only.yaml")
                    .replace("maxDuration: 45m", "maxDuration: 45 minutes");
            var outcome = service.validate(document);

            assertThat(outcome.findings().codes()).contains("spec.parse.duration.invalid");
        }

        @Test
        @DisplayName("structural and semantic findings are reported together in one response")
        void reportsAllLayersAtOnce() {
            String document = ExampleFixtures.valid("api-only.yaml")
                    .replace("      sourceRef: proposal-api", "      sourceRef: missing-source")
                    .replace("    maxDuration: 45m", "    maxDurationn: 45m");
            var outcome = service.validate(document);

            assertThat(outcome.findings().codes())
                    .contains("spec.parse.unknownField", "spec.workload.sourceRef.unresolved");
        }

        @Test
        @DisplayName("findings are ordered errors first")
        void ordersErrorsFirst() {
            var outcome = service.validate(ExampleFixtures.invalid("unresolved-references.yaml"));

            assertThat(outcome.findings().findings())
                    .isSortedAccordingTo((a, b) -> b.severity().compareTo(a.severity()));
            assertThat(outcome.findings().findings().get(0).severity()).isEqualTo(Severity.ERROR);
        }
    }

    @Nested
    @DisplayName("digest")
    class Digest {

        @Test
        @DisplayName("reformatting a document does not change its digest")
        void reformattingDoesNotChangeDigest() {
            String original = ExampleFixtures.valid("api-only.yaml");
            // Strip comments and blank lines: same test, different bytes.
            String reformatted = original.lines()
                    .filter(line -> !line.trim().startsWith("#"))
                    .filter(line -> !line.isBlank())
                    .reduce("", (a, b) -> a + b + "\n");

            assertThat(service.validate(reformatted).digest())
                    .isEqualTo(service.validate(original).digest());
        }

        @Test
        @DisplayName("changing a target user count changes the digest")
        void materialChangeChangesDigest() {
            String original = ExampleFixtures.valid("api-only.yaml");
            String changed = original.replace("targetUsers: 600", "targetUsers: 601");

            assertThat(service.validate(changed).digest())
                    .isNotEqualTo(service.validate(original).digest());
        }
    }
}
