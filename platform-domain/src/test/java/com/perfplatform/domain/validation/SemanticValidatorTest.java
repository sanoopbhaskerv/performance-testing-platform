package com.perfplatform.domain.validation;

import com.perfplatform.domain.spec.PerformanceTestSpecification;
import com.perfplatform.domain.spec.PerformanceTestSpecification.EnvironmentSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.GovernanceSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.LoadModel;
import com.perfplatform.domain.spec.PerformanceTestSpecification.LoadStage;
import com.perfplatform.domain.spec.PerformanceTestSpecification.ObjectiveSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.ScenarioSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.SourceSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.SpecMetadata;
import com.perfplatform.domain.spec.PerformanceTestSpecification.StartCondition;
import com.perfplatform.domain.spec.PerformanceTestSpecification.TestSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.WorkloadSpec;
import com.perfplatform.domain.spec.SourceType;
import com.perfplatform.domain.spec.SpecificationFixtures;
import com.perfplatform.domain.spec.Threshold;
import com.perfplatform.domain.spec.WorkloadType;
import com.perfplatform.domain.spec.LoadModelType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Each test derives an invalid specification from a valid fixture by making one deliberate
 * change, and asserts the stable finding code a CI pipeline would key on.
 */
class SemanticValidatorTest {

    @Nested
    @DisplayName("valid fixtures")
    class ValidFixtures {

        @Test
        @DisplayName("the API-only example is valid")
        void apiOnlyIsValid() {
            ValidationResult result = SemanticValidator.validate(SpecificationFixtures.apiOnly());

            assertThat(result.errors()).isEmpty();
            assertThat(result.isValid()).isTrue();
        }

        @Test
        @DisplayName("the browser-only example is valid")
        void browserOnlyIsValid() {
            ValidationResult result = SemanticValidator.validate(SpecificationFixtures.browserOnly());

            assertThat(result.errors()).isEmpty();
            assertThat(result.isValid()).isTrue();
        }

        @Test
        @DisplayName("the hybrid example is valid")
        void hybridIsValid() {
            ValidationResult result = SemanticValidator.validate(SpecificationFixtures.hybrid());

            assertThat(result.errors()).isEmpty();
            assertThat(result.isValid()).isTrue();
        }
    }

    @Nested
    @DisplayName("document envelope")
    class DocumentEnvelope {

        @Test
        @DisplayName("an unsupported apiVersion is rejected")
        void rejectsUnsupportedApiVersion() {
            PerformanceTestSpecification spec = SpecificationFixtures.apiOnly();
            var changed = new PerformanceTestSpecification("performance.ameriprise.com/v2",
                    spec.kind(), spec.metadata(), spec.spec());

            assertThat(SemanticValidator.validate(changed).codes())
                    .contains("spec.apiVersion.unsupported");
        }

        @Test
        @DisplayName("a missing kind is rejected")
        void rejectsMissingKind() {
            PerformanceTestSpecification spec = SpecificationFixtures.apiOnly();
            var changed = new PerformanceTestSpecification(spec.apiVersion(), null,
                    spec.metadata(), spec.spec());

            assertThat(SemanticValidator.validate(changed).codes()).contains("spec.kind.missing");
        }

        @Test
        @DisplayName("a null specification produces a single finding rather than throwing")
        void handlesNullSpecification() {
            ValidationResult result = SemanticValidator.validate(null);

            assertThat(result.codes()).containsExactly("spec.missing");
        }
    }

    @Nested
    @DisplayName("names")
    class Names {

        @Test
        @DisplayName("an uppercase name is rejected because names become object keys and labels")
        void rejectsInvalidName() {
            var result = SemanticValidator.validate(withMetadata(
                    new SpecMetadata("Proposal_Workflow", "advice-implementation", "proposal", Map.of())));

            assertThat(result.codes()).contains("spec.name.invalid");
        }

        @Test
        @DisplayName("a name longer than 63 characters is rejected")
        void rejectsOverlongName() {
            var result = SemanticValidator.validate(withMetadata(
                    new SpecMetadata("a".repeat(64), "advice-implementation", "proposal", Map.of())));

            assertThat(result.codes()).contains("spec.name.tooLong");
        }

        @Test
        @DisplayName("a missing team is rejected, because authorization starts there")
        void rejectsMissingTeam() {
            var result = SemanticValidator.validate(withMetadata(
                    new SpecMetadata("proposal-workflow", null, "proposal", Map.of())));

            assertThat(result.errors())
                    .anyMatch(finding -> finding.path().equals("/metadata/team"));
        }
    }

    @Nested
    @DisplayName("workloads")
    class Workloads {

        @Test
        @DisplayName("a specification with no workloads is rejected")
        void rejectsNoWorkloads() {
            assertThat(SemanticValidator.validate(withWorkloads(List.of())).codes())
                    .contains("spec.workload.missing");
        }

        @Test
        @DisplayName("a protocol workload with no load model is rejected")
        void rejectsProtocolWorkloadWithoutModel() {
            var workload = new WorkloadSpec("api-load", WorkloadType.PROTOCOL, "jmeter",
                    null, null, List.of(), null, null, null);

            assertThat(SemanticValidator.validate(withWorkloads(List.of(workload))).codes())
                    .contains("spec.workload.model.missing");
        }

        @Test
        @DisplayName("a workload whose every stage targets zero users is rejected")
        void rejectsWorkloadGeneratingNoLoad() {
            var workload = new WorkloadSpec("api-load", WorkloadType.PROTOCOL, "jmeter", null,
                    new LoadModel(LoadModelType.STAGES,
                            List.of(new LoadStage(Duration.ofMinutes(5), 0))),
                    List.of(), null, null, null);

            assertThat(SemanticValidator.validate(withWorkloads(List.of(workload))).codes())
                    .contains("spec.workload.stage.users.allZero");
        }

        @Test
        @DisplayName("a stage with a zero duration is rejected")
        void rejectsZeroDurationStage() {
            var workload = new WorkloadSpec("api-load", WorkloadType.PROTOCOL, "jmeter", null,
                    new LoadModel(LoadModelType.STAGES,
                            List.of(new LoadStage(Duration.ZERO, 100))),
                    List.of(), null, null, null);

            assertThat(SemanticValidator.validate(withWorkloads(List.of(workload))).codes())
                    .contains("spec.workload.stage.duration.invalid");
        }

        // ── The open model ───────────────────────────────────────────────────
        //
        // The rules here exist because an open model can destroy the thing measuring it. Arrivals
        // keep producing requests whatever the target does with them, so a stall accumulates work
        // without bound until the generator fails — and reports its own failure as the target's.

        @Test
        @DisplayName("an arrival-rate model with no concurrency ceiling is rejected outright")
        void rejectsArrivalRateWithoutCeiling() {
            var workload = arrivalRate(null, new LoadStage(Duration.ofMinutes(2), 0, 400));

            assertThat(SemanticValidator.validate(withWorkloads(List.of(workload))).codes())
                    .contains("spec.workload.model.maxConcurrency.missing");
        }

        @Test
        @DisplayName("a ceiling implying an unreachable response time is a warning, with the figure")
        void warnsWhenTheImpliedResponseBudgetIsTight() {
            // Little's law: 400 a second through 20 in flight needs every response inside 50ms.
            // Achievable for a cache lookup and not for much else — so if the target is slower, the
            // ceiling rather than the rate is what limits the run. A warning rather than an error,
            // because throttling deliberately is a legitimate thing to want.
            var workload = arrivalRate(20, new LoadStage(Duration.ofMinutes(2), 0, 400));

            var findings = SemanticValidator.validate(withWorkloads(List.of(workload)));

            assertThat(findings.codes()).contains("spec.workload.model.maxConcurrency.tight");
            assertThat(findings.errors()).isEmpty();
            assertThat(findings.findings())
                    .filteredOn(finding -> finding.code()
                            .equals("spec.workload.model.maxConcurrency.tight"))
                    .singleElement()
                    // The figure, not a prescription: only the author knows how fast the endpoint is.
                    .satisfies(finding -> assertThat(finding.message()).contains("within 50ms"));
        }

        @Test
        @DisplayName("a ceiling that leaves an ordinary API room passes without comment")
        void acceptsASufficientCeiling() {
            // 250 in flight at 400 a second implies a 625ms budget, which is unremarkable.
            var workload = arrivalRate(250, new LoadStage(Duration.ofMinutes(2), 0, 400));

            assertThat(SemanticValidator.validate(withWorkloads(List.of(workload))).codes())
                    .doesNotContain("spec.workload.model.maxConcurrency.tight",
                            "spec.workload.model.maxConcurrency.missing");
        }

        @Test
        @DisplayName("an arrival-rate stage with no rate is rejected")
        void rejectsArrivalRateStageWithoutRate() {
            var workload = arrivalRate(100, new LoadStage(Duration.ofMinutes(2), 0, null));

            assertThat(SemanticValidator.validate(withWorkloads(List.of(workload))).codes())
                    .contains("spec.workload.stage.rate.missing");
        }

        @Test
        @DisplayName("every stage at zero requests per second generates nothing, and is rejected")
        void rejectsAllZeroRates() {
            var workload = arrivalRate(100, new LoadStage(Duration.ofMinutes(2), 0, 0));

            assertThat(SemanticValidator.validate(withWorkloads(List.of(workload))).codes())
                    .contains("spec.workload.stage.rate.allZero");
        }

        @Test
        @DisplayName("a user count in an open model is refused, not ignored")
        void rejectsUsersInAnOpenModel() {
            // Ignoring it would leave the author believing they had capped concurrency at 50 when
            // the only thing capping it is maxConcurrency.
            var workload = arrivalRate(250, new LoadStage(Duration.ofMinutes(2), 50, 400));

            assertThat(SemanticValidator.validate(withWorkloads(List.of(workload))).codes())
                    .contains("spec.workload.stage.users.notApplicable");
        }

        @Test
        @DisplayName("a rate in a closed model is refused, not ignored")
        void rejectsRateInAClosedModel() {
            // Whoever wrote this expects 400 requests a second. The run will send whatever 50 users
            // produce, which is a different and unknown number — so they would be reading a report
            // against an expectation the platform never agreed to.
            var workload = new WorkloadSpec("api-load", WorkloadType.PROTOCOL, "jmeter", null,
                    new LoadModel(LoadModelType.STAGES,
                            List.of(new LoadStage(Duration.ofMinutes(2), 50, 400))),
                    List.of(), null, null, null);

            assertThat(SemanticValidator.validate(withWorkloads(List.of(workload))).codes())
                    .contains("spec.workload.stage.rate.notApplicable");
        }

        private WorkloadSpec arrivalRate(Integer maxConcurrency, LoadStage... stages) {
            return new WorkloadSpec("api-rate", WorkloadType.PROTOCOL, "jmeter", null,
                    new LoadModel(LoadModelType.ARRIVAL_RATE, List.of(stages), maxConcurrency),
                    List.of(), null, null, null);
        }

        @Test
        @DisplayName("duplicate workload names are rejected, because objectives reference them")
        void rejectsDuplicateWorkloadNames() {
            var workload = SpecificationFixtures.apiWorkload();

            assertThat(SemanticValidator.validate(withWorkloads(List.of(workload, workload))).codes())
                    .contains("spec.workload.name.duplicate");
        }

        @Test
        @DisplayName("a sourceRef that matches no declared source is rejected")
        void rejectsUnresolvedSourceRef() {
            var workload = new WorkloadSpec("api-load", WorkloadType.PROTOCOL, "jmeter",
                    "does-not-exist",
                    new LoadModel(LoadModelType.STAGES,
                            List.of(new LoadStage(Duration.ofMinutes(5), 100))),
                    List.of(), null, null, null);

            assertThat(SemanticValidator.validate(withWorkloads(List.of(workload))).codes())
                    .contains("spec.workload.sourceRef.unresolved");
        }

        @Test
        @DisplayName("a browser workload with no browserUsers is rejected")
        void rejectsBrowserWorkloadWithoutUsers() {
            var workload = new WorkloadSpec("journey", WorkloadType.BROWSER, "playwright",
                    null, null, List.of(), null, null, 3);

            assertThat(SemanticValidator.validate(withWorkloads(List.of(workload))).codes())
                    .contains("spec.workload.browserUsers.missing");
        }

        @Test
        @DisplayName("a startWhen referencing an unknown workload is rejected")
        void rejectsUnresolvedStartCondition() {
            var browser = new WorkloadSpec("journey", WorkloadType.BROWSER, "playwright", null,
                    null, List.of(), new StartCondition("nonexistent", "steady-state"), 5, 3);

            assertThat(SemanticValidator.validate(withWorkloads(List.of(browser))).codes())
                    .contains("spec.workload.startWhen.unresolved");
        }

        @Test
        @DisplayName("a workload waiting on itself is rejected")
        void rejectsSelfReferencingStartCondition() {
            var browser = new WorkloadSpec("journey", WorkloadType.BROWSER, "playwright", null,
                    null, List.of(), new StartCondition("journey", "steady-state"), 5, 3);

            assertThat(SemanticValidator.validate(withWorkloads(List.of(browser))).codes())
                    .contains("spec.workload.startWhen.selfReference");
        }
    }

    @Nested
    @DisplayName("scenarios")
    class Scenarios {

        @Test
        @DisplayName("weights that do not sum to 100 warn but do not block")
        void warnsOnWeightsNotSummingToOneHundred() {
            var workload = workloadWithScenarios(List.of(
                    new ScenarioSpec("a", 60, "opA"),
                    new ScenarioSpec("b", 30, "opB"),
                    new ScenarioSpec("c", 20, "opC")));

            ValidationResult result = SemanticValidator.validate(withWorkloads(List.of(workload)));

            assertThat(result.codes()).contains("spec.scenario.weights.sum");
            assertThat(result.isValid()).isTrue();
            assertThat(result.warnings())
                    .anyMatch(finding -> finding.message().contains("110"));
        }

        @Test
        @DisplayName("a zero weight is rejected, because the scenario would never run")
        void rejectsZeroWeight() {
            var workload = workloadWithScenarios(List.of(
                    new ScenarioSpec("a", 100, "opA"),
                    new ScenarioSpec("b", 0, "opB")));

            assertThat(SemanticValidator.validate(withWorkloads(List.of(workload))).codes())
                    .contains("spec.scenario.weight.invalid");
        }

        @Test
        @DisplayName("duplicate scenario names are rejected")
        void rejectsDuplicateScenarioNames() {
            var workload = workloadWithScenarios(List.of(
                    new ScenarioSpec("a", 50, "opA"),
                    new ScenarioSpec("a", 50, "opB")));

            assertThat(SemanticValidator.validate(withWorkloads(List.of(workload))).codes())
                    .contains("spec.scenario.name.duplicate");
        }
    }

    @Nested
    @DisplayName("sources")
    class Sources {

        @Test
        @DisplayName("a Playwright source pinned to a branch is rejected as mutable")
        void rejectsMutableGitRef() {
            var source = new SourceSpec("browser", SourceType.PLAYWRIGHT, null,
                    new SourceSpec.GitSource("ssh://bitbucket/e2e.git", "main",
                            "tests/journey.spec.ts", null));

            assertThat(SemanticValidator.validate(withSources(List.of(source))).codes())
                    .contains("spec.source.git.ref.mutable");
        }

        @Test
        @DisplayName("a commit SHA is accepted as an immutable ref")
        void acceptsCommitSha() {
            assertThat(SemanticValidator.validate(SpecificationFixtures.browserOnly()).codes())
                    .doesNotContain("spec.source.git.ref.mutable");
        }

        @Test
        @DisplayName("a Playwright source may be a stored artifact with a digest")
        void acceptsPlaywrightArtifact() {
            var source = new SourceSpec("browser", SourceType.PLAYWRIGHT,
                    "artifact://playwright-sources/journey.spec.ts",
                    "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                    null);

            assertThat(SemanticValidator.validate(withSources(List.of(source))).codes())
                    .doesNotContain("spec.source.playwright.material.missing",
                            "spec.source.playwright.digest.missing",
                            "spec.source.playwright.digest.invalid");
        }

        @Test
        @DisplayName("a Playwright artifact without a digest is rejected")
        void rejectsPlaywrightArtifactWithoutDigest() {
            var source = new SourceSpec("browser", SourceType.PLAYWRIGHT,
                    "artifact://playwright-sources/journey.spec.ts", null, null);

            assertThat(SemanticValidator.validate(withSources(List.of(source))).codes())
                    .contains("spec.source.playwright.digest.missing");
        }

        @Test
        @DisplayName("a path escaping the checked-out workspace is rejected")
        void rejectsPathTraversal() {
            var source = new SourceSpec("browser", SourceType.PLAYWRIGHT, null,
                    new SourceSpec.GitSource("ssh://bitbucket/e2e.git",
                            "8f121c7a9d4e2b6c1f0a3e5d7c9b8a4f6e2d1c0b",
                            "../../etc/passwd", null));

            assertThat(SemanticValidator.validate(withSources(List.of(source))).codes())
                    .contains("spec.source.git.path.traversal");
        }

        @Test
        @DisplayName("duplicate source names are rejected")
        void rejectsDuplicateSourceNames() {
            var source = new SourceSpec("api", SourceType.OPENAPI, "artifact://a.yaml", null);

            assertThat(SemanticValidator.validate(withSources(List.of(source, source))).codes())
                    .contains("spec.source.name.duplicate");
        }

        @Test
        @DisplayName("a document source with no uri is rejected")
        void rejectsDocumentSourceWithoutUri() {
            var source = new SourceSpec("api", SourceType.OPENAPI, null, null);

            assertThat(SemanticValidator.validate(withSources(List.of(source))).codes())
                    .contains("spec.source.uri.missing");
        }
    }

    @Nested
    @DisplayName("objectives")
    class Objectives {

        @Test
        @DisplayName("an objective scoped to an unknown workload is rejected")
        void rejectsUnresolvedScope() {
            var objective = new ObjectiveSpec("http.response.time", "nonexistent", 95,
                    Threshold.parse("750ms"));

            assertThat(SemanticValidator.validate(withObjectives(List.of(objective))).codes())
                    .contains("spec.objective.scope.unresolved");
        }

        /**
         * Samplers are labelled {@code workload/scenario} precisely so a scenario-scoped objective
         * resolves — that was defect D4's fix. A validator that refused the label the results
         * actually carry would reject an objective the evaluator handles perfectly well, and the
         * author would have no way to tell that the platform disagreed with itself.
         */
        @Test
        @DisplayName("an objective scoped to one scenario within a workload resolves")
        void acceptsScenarioScope() {
            var workloads = SpecificationFixtures.apiOnly().spec().workloads();
            String scope = workloads.get(0).name() + "/" + workloads.get(0).scenarios().get(0).name();

            var objective = new ObjectiveSpec("http.response.time", scope, 95,
                    Threshold.parse("750ms"));

            assertThat(SemanticValidator.validate(withObjectives(List.of(objective))).codes())
                    .doesNotContain("spec.objective.scope.unresolved");
        }

        @Test
        @DisplayName("a scenario name from a different workload is still rejected")
        void rejectsScenarioScopeFromAnotherWorkload() {
            var objective = new ObjectiveSpec("http.response.time", "no-such-workload/browse", 95,
                    Threshold.parse("750ms"));

            assertThat(SemanticValidator.validate(withObjectives(List.of(objective))).codes())
                    .contains("spec.objective.scope.unresolved");
        }

        @Test
        @DisplayName("a percentile outside 1-100 is rejected")
        void rejectsInvalidPercentile() {
            var objective = new ObjectiveSpec("http.response.time", null, 150,
                    Threshold.parse("750ms"));

            assertThat(SemanticValidator.validate(withObjectives(List.of(objective))).codes())
                    .contains("spec.objective.percentile.invalid");
        }

        @Test
        void rejectsUnitsThatDoNotMatchTheMetric() {
            var objective = new ObjectiveSpec("http.error.rate", null, null, Threshold.parse("1s"));
            assertThat(SemanticValidator.validate(withObjectives(List.of(objective))).codes())
                    .contains("spec.objective.threshold.unit");
        }

        @Test
        void rejectsPercentilesThatCannotBeMeasuredFromTheSummary() {
            var objective = new ObjectiveSpec("http.response.time", null, 98, Threshold.parse("200ms"));
            assertThat(SemanticValidator.validate(withObjectives(List.of(objective))).codes())
                    .contains("spec.objective.percentile.invalid");
        }

        @Test
        @DisplayName("declaring no objectives warns, because the test cannot pass or fail")
        void warnsOnNoObjectives() {
            ValidationResult result = SemanticValidator.validate(withObjectives(List.of()));

            assertThat(result.codes()).contains("spec.objective.missing");
            assertThat(result.isValid()).isTrue();
        }
    }

    @Nested
    @DisplayName("secrets and governance")
    class SecretsAndGovernance {

        @Test
        @DisplayName("an inline credential in secretRef is rejected")
        void rejectsInlineSecret() {
            var spec = withAuthentication("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.abc123");

            assertThat(SemanticValidator.validate(spec).codes()).contains("spec.secret.inline-value");
        }

        @Test
        @DisplayName("the finding for an inline credential never echoes the value")
        void doesNotEchoSecretValue() {
            String secret = "ghp_abcdefghijklmnopqrstuvwxyz0123456789";
            ValidationResult result = SemanticValidator.validate(withAuthentication(secret));

            assertThat(result.findings())
                    .allSatisfy(finding -> {
                        assertThat(finding.message()).doesNotContain(secret);
                        assertThat(finding.remediation() == null ? "" : finding.remediation())
                                .doesNotContain(secret);
                    });
        }

        @Test
        @DisplayName("a vault reference is accepted")
        void acceptsVaultReference() {
            assertThat(SemanticValidator.validate(
                    withAuthentication("vault://performance/proposal/client")).codes())
                    .doesNotContain("spec.secret.inline-value");
        }

        @Test
        @DisplayName("a run-scoped credential reference is accepted")
        void acceptsRunScopedReference() {
            assertThat(SemanticValidator.validate(
                    withAuthentication("run://session")).codes())
                    .doesNotContain("spec.secret.inline-value");
        }

        @Test
        @DisplayName("a workload authRef must resolve to an authentication entry")
        void rejectsUnresolvedAuthRef() {
            assertThat(SemanticValidator.validate(withWorkloadAuthRef("missing-auth")).codes())
                    .contains("spec.workload.authRef.unresolved");
        }

        @Test
        @DisplayName("an authentication entry used by a workload must declare how it is injected")
        void rejectsMissingInjectionTarget() {
            assertThat(SemanticValidator.validate(withReferencedAuthentication(null)).codes())
                    .contains("spec.authentication.injectAs.missing");
        }

        @Test
        @DisplayName("stages running past maxDuration are rejected rather than silently truncated")
        void rejectsStagesExceedingMaxDuration() {
            PerformanceTestSpecification base = SpecificationFixtures.apiOnly();
            var body = base.spec();
            var changed = new PerformanceTestSpecification(base.apiVersion(), base.kind(),
                    base.metadata(),
                    new TestSpec(body.environment(), body.sources(), body.authentication(),
                            body.datasets(), body.workloads(), body.objectives(),
                            body.observability(),
                            new GovernanceSpec(Duration.ofMinutes(5), "non-production-standard",
                                    Duration.ofDays(90))));

            assertThat(SemanticValidator.validate(changed).codes())
                    .contains("spec.governance.maxDuration.exceeded");
        }
    }

    @Test
    @DisplayName("findings are ordered errors first and deduplicated")
    void findingsAreOrderedAndDeduplicated() {
        ValidationResult result = SemanticValidator.validate(withWorkloads(List.of()));
        ValidationResult doubled = result.merge(result);

        assertThat(doubled.findings()).isEqualTo(result.findings());
        assertThat(result.findings())
                .isSortedAccordingTo((a, b) -> b.severity().compareTo(a.severity()));
    }

    @Nested
    @DisplayName("dataset references")
    class DatasetReferences {

        /**
         * The one that would be tempting to allow. {@code dataset://orders} reads naturally and
         * means "whatever orders holds today" — so two runs of one immutable definition version
         * would read different rows while carrying the same specification digest. Refused for the
         * same reason a floating runner image tag is refused at startup.
         */
        @Test
        @DisplayName("an unversioned reference is an error, with the fix named")
        void unversionedIsRefused() {
            ValidationResult result = SemanticValidator.validate(
                    withDatasetUri("dataset://proposal-clients"));

            assertThat(result.codes()).contains("spec.dataset.source.uri.unversioned");
            assertThat(result.isValid()).isFalse();
        }

        @Test
        @DisplayName("a bucket URL or file path is an error: it names nothing this platform stores")
        void unresolvableIsRefused() {
            ValidationResult result = SemanticValidator.validate(
                    withDatasetUri("s3://some-bucket/clients.csv"));

            assertThat(result.codes()).contains("spec.dataset.source.uri.unresolvable");
            assertThat(result.isValid()).isFalse();
        }

        @Test
        @DisplayName("a pinned reference is accepted")
        void pinnedIsAccepted() {
            ValidationResult result = SemanticValidator.validate(
                    withDatasetUri("dataset://proposal-clients/4"));

            assertThat(result.codes())
                    .doesNotContain("spec.dataset.source.uri.unversioned")
                    .doesNotContain("spec.dataset.source.uri.unresolvable");
            assertThat(result.errors()).isEmpty();
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static PerformanceTestSpecification withDatasetUri(String uri) {
        PerformanceTestSpecification base = SpecificationFixtures.apiOnly();
        var body = base.spec();
        var dataset = body.datasets().get(0);
        return new PerformanceTestSpecification(base.apiVersion(), base.kind(), base.metadata(),
                new TestSpec(body.environment(), body.sources(), body.authentication(),
                        List.of(new PerformanceTestSpecification.DatasetSpec(dataset.name(),
                                new PerformanceTestSpecification.DatasetSpec.DataSource(
                                        "object-storage", uri),
                                dataset.columns(), dataset.partitionByShard())),
                        body.workloads(), body.objectives(), body.observability(),
                        body.governance()));
    }

    private static PerformanceTestSpecification withMetadata(SpecMetadata metadata) {
        PerformanceTestSpecification base = SpecificationFixtures.apiOnly();
        return new PerformanceTestSpecification(base.apiVersion(), base.kind(), metadata, base.spec());
    }

    private static PerformanceTestSpecification withWorkloads(List<WorkloadSpec> workloads) {
        PerformanceTestSpecification base = SpecificationFixtures.apiOnly();
        var body = base.spec();
        return new PerformanceTestSpecification(base.apiVersion(), base.kind(), base.metadata(),
                new TestSpec(body.environment(), body.sources(), body.authentication(),
                        body.datasets(), workloads, List.of(), body.observability(),
                        body.governance()));
    }

    private static PerformanceTestSpecification withSources(List<SourceSpec> sources) {
        PerformanceTestSpecification base = SpecificationFixtures.apiOnly();
        var body = base.spec();
        var workload = new WorkloadSpec("api-load", WorkloadType.PROTOCOL, "jmeter", null,
                new LoadModel(LoadModelType.STAGES,
                        List.of(new LoadStage(Duration.ofMinutes(5), 100))),
                List.of(), null, null, null);
        return new PerformanceTestSpecification(base.apiVersion(), base.kind(), base.metadata(),
                new TestSpec(body.environment(), sources, body.authentication(), body.datasets(),
                        List.of(workload), List.of(), body.observability(), body.governance()));
    }

    private static PerformanceTestSpecification withObjectives(List<ObjectiveSpec> objectives) {
        PerformanceTestSpecification base = SpecificationFixtures.apiOnly();
        var body = base.spec();
        return new PerformanceTestSpecification(base.apiVersion(), base.kind(), base.metadata(),
                new TestSpec(body.environment(), body.sources(), body.authentication(),
                        body.datasets(), body.workloads(), objectives, body.observability(),
                        body.governance()));
    }

    private static PerformanceTestSpecification withAuthentication(String secretRef) {
        PerformanceTestSpecification base = SpecificationFixtures.apiOnly();
        var body = base.spec();
        return new PerformanceTestSpecification(base.apiVersion(), base.kind(), base.metadata(),
                new TestSpec(body.environment(), body.sources(),
                        List.of(new PerformanceTestSpecification.AuthenticationSpec(
                                "test-user", "oauth2-client-credentials", secretRef)),
                        body.datasets(), body.workloads(), body.objectives(), body.observability(),
                        body.governance()));
    }

    private static PerformanceTestSpecification withWorkloadAuthRef(String authRef) {
        PerformanceTestSpecification base = SpecificationFixtures.apiOnly();
        var body = base.spec();
        WorkloadSpec workload = body.workloads().get(0);
        WorkloadSpec changed = new WorkloadSpec(workload.name(), workload.type(),
                workload.engine(), workload.sourceRef(), authRef, workload.model(),
                workload.scenarios(), workload.startWhen(), workload.browserUsers(),
                workload.iterationsPerUser());
        return new PerformanceTestSpecification(base.apiVersion(), base.kind(), base.metadata(),
                new TestSpec(body.environment(), body.sources(), body.authentication(),
                        body.datasets(), List.of(changed), body.objectives(),
                        body.observability(), body.governance()));
    }

    private static PerformanceTestSpecification withReferencedAuthentication(String injectAs) {
        PerformanceTestSpecification base = withWorkloadAuthRef("session");
        var body = base.spec();
        return new PerformanceTestSpecification(base.apiVersion(), base.kind(), base.metadata(),
                new TestSpec(body.environment(), body.sources(),
                        List.of(new PerformanceTestSpecification.AuthenticationSpec(
                                "session", "bearer", injectAs, "run://session")),
                        body.datasets(), body.workloads(), body.objectives(),
                        body.observability(), body.governance()));
    }

    private static WorkloadSpec workloadWithScenarios(List<ScenarioSpec> scenarios) {
        return new WorkloadSpec("api-load", WorkloadType.PROTOCOL, "jmeter", null,
                new LoadModel(LoadModelType.STAGES,
                        List.of(new LoadStage(Duration.ofMinutes(5), 100))),
                scenarios, null, null, null);
    }
}
