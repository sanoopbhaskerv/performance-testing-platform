package com.perfplatform.domain.plan;

import com.perfplatform.domain.identity.ExecutionPlanId;
import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.identity.TestDefinitionId;
import com.perfplatform.domain.identity.TestDefinitionVersionId;
import com.perfplatform.domain.port.DefinitionVersionCatalog.DefinitionVersion;
import com.perfplatform.domain.spec.PerformanceTestSpecification;
import com.perfplatform.domain.spec.SpecificationDigest;
import com.perfplatform.domain.spec.SpecificationFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The baseline plan compiler.
 *
 * <p>The determinism tests matter most. The plan's digest is what runner results are verified
 * against, so a compiler that emitted fields in a different order between runs would reject valid
 * results at random — a failure that is very hard to diagnose from the outside.
 */
class BaselineExecutionPlanCompilerTest {

    private static final Instant NOW = Instant.parse("2026-07-29T10:00:00Z");

    private final ExecutionPlanCompiler compiler = new BaselineExecutionPlanCompiler();

    private static DefinitionVersion versionOf(PerformanceTestSpecification specification) {
        return versionWithDigest(specification, SpecificationDigest.of(specification));
    }

    private static DefinitionVersion versionWithDigest(PerformanceTestSpecification specification,
            SpecificationDigest digest) {
        return new DefinitionVersion(
                TestDefinitionVersionId.generate(),
                TestDefinitionId.generate(),
                TeamId.generate(),
                1,
                digest,
                specification,
                true);
    }

    @Nested
    @DisplayName("determinism")
    class Determinism {

        @Test
        @DisplayName("the same specification compiles to the same plan text and digest")
        void sameSpecificationSameDigest() {
            DefinitionVersion version = versionOf(SpecificationFixtures.apiOnly());

            ExecutionPlan first = compiler.compile(ExecutionPlanId.generate(), version, NOW);
            ExecutionPlan second = compiler.compile(ExecutionPlanId.generate(), version, NOW);

            assertThat(second.planJson()).isEqualTo(first.planJson());
            assertThat(second.planDigest()).isEqualTo(first.planDigest());
        }

        /**
         * Changing the traffic mix changes what is being measured, so it must change the plan's
         * identity even when the endpoints are untouched.
         */
        @Test
        @DisplayName("changing a scenario weight changes the digest")
        void scenarioWeightAffectsDigest() {
            ExecutionPlan original = compiler.compile(ExecutionPlanId.generate(),
                    versionOf(SpecificationFixtures.apiOnly()), NOW);

            PerformanceTestSpecification reweighted = SpecificationFixtures.apiOnly();
            var workload = reweighted.spec().workloads().get(0);
            var scenarios = new java.util.ArrayList<>(workload.scenarios());
            scenarios.set(0, new PerformanceTestSpecification.ScenarioSpec(
                    scenarios.get(0).name(), scenarios.get(0).weight() + 5,
                    scenarios.get(0).operationId()));

            PerformanceTestSpecification changed = withScenarios(reweighted, scenarios);

            assertThat(compiler.compile(ExecutionPlanId.generate(), versionOf(changed), NOW)
                    .planDigest())
                    .isNotEqualTo(original.planDigest());
        }

        /**
         * The plan text must not depend on the order scenarios happen to be listed in, because that
         * order is not part of what the test measures. Compared with the version digest held fixed:
         * reordering a specification does change the <em>specification's</em> identity, and the plan
         * legitimately embeds that — this is about the plan's own rendering, not about the version.
         */
        @Test
        @DisplayName("declaration order of scenarios does not change the rendered plan")
        void scenarioOrderDoesNotAffectPlanText() {
            PerformanceTestSpecification specification = SpecificationFixtures.apiOnly();
            SpecificationDigest fixedDigest = SpecificationDigest.of(specification);

            var reordered = new java.util.ArrayList<>(specification.spec().workloads().get(0).scenarios());
            java.util.Collections.reverse(reordered);

            ExecutionPlan asDeclared = compiler.compile(ExecutionPlanId.generate(),
                    versionWithDigest(specification, fixedDigest), NOW);
            ExecutionPlan asReversed = compiler.compile(ExecutionPlanId.generate(),
                    versionWithDigest(withScenarios(specification, reordered), fixedDigest), NOW);

            assertThat(asReversed.planJson()).isEqualTo(asDeclared.planJson());
            assertThat(asReversed.planDigest()).isEqualTo(asDeclared.planDigest());
        }

        @Test
        @DisplayName("scenarios are rendered in alphabetical order")
        void scenariosAreSorted() {
            String planJson = compiler.compile(ExecutionPlanId.generate(),
                    versionOf(SpecificationFixtures.apiOnly()), NOW).planJson();

            var names = SpecificationFixtures.apiOnly().spec().workloads().get(0).scenarios().stream()
                    .map(PerformanceTestSpecification.ScenarioSpec::name)
                    .sorted()
                    .toList();

            int previous = -1;
            for (String name : names) {
                int at = planJson.indexOf("\"name\":\"" + name + "\"");
                assertThat(at).as("scenario %s appears in the plan", name).isGreaterThan(previous);
                previous = at;
            }
        }

        @Test
        @DisplayName("the digest is over the plan text, not the specification")
        void digestIsOverPlanText() {
            ExecutionPlan plan = compiler.compile(ExecutionPlanId.generate(),
                    versionOf(SpecificationFixtures.apiOnly()), NOW);

            assertThat(plan.planDigest())
                    .isEqualTo(SpecificationDigest.ofCanonicalForm(plan.planJson()));
            assertThat(plan.matchesDigest(plan.planDigest())).isTrue();
        }

        @Test
        @DisplayName("a digest from another plan is rejected")
        void mismatchedDigestRejected() {
            ExecutionPlan plan = compiler.compile(ExecutionPlanId.generate(),
                    versionOf(SpecificationFixtures.apiOnly()), NOW);

            assertThat(plan.matchesDigest(SpecificationDigest.ofCanonicalForm("something else")))
                    .isFalse();
            assertThat(plan.matchesDigest(null)).isFalse();
        }
    }

    @Nested
    @DisplayName("capacity estimate")
    class Capacity {

        @Test
        @DisplayName("peak users is the specification's peak protocol users")
        void peakUsers() {
            PerformanceTestSpecification specification = SpecificationFixtures.apiOnly();

            assertThat(compiler.compile(ExecutionPlanId.generate(), versionOf(specification), NOW)
                    .capacity().peakVirtualUsers())
                    .isEqualTo(specification.peakProtocolUsers())
                    .isPositive();
        }

        /**
         * Sharding is only safe once a calibration profile says how much one runner can carry
         * (CLAUDE.md §15). Guessing a larger number spreads load across generators whose ceiling
         * nobody has measured, which is how a load test ends up measuring its own generator.
         */
        @Test
        @DisplayName("one shard until calibration says otherwise")
        void oneShardUntilCalibrated() {
            assertThat(compiler.compile(ExecutionPlanId.generate(),
                    versionOf(SpecificationFixtures.apiOnly()), NOW).capacity().shardCount())
                    .isEqualTo(1);
        }

        /**
         * The longest workload, not the sum: workloads run concurrently, so summing would set a
         * deadline several times longer than the test and delay detection of a hung run.
         */
        @Test
        @DisplayName("duration is the longest workload, not the total across workloads")
        void durationIsLongestWorkload() {
            PerformanceTestSpecification hybrid = SpecificationFixtures.hybrid();

            long longest = hybrid.spec().workloads().stream()
                    .mapToLong(workload -> workload.totalDuration().toSeconds())
                    .max().orElseThrow();
            long sum = hybrid.spec().workloads().stream()
                    .mapToLong(workload -> workload.totalDuration().toSeconds())
                    .sum();

            assertThat(compiler.compile(ExecutionPlanId.generate(), versionOf(hybrid), NOW)
                    .capacity().estimatedDurationSeconds())
                    .isEqualTo(longest)
                    .isLessThanOrEqualTo(sum);
        }
    }

    @Nested
    @DisplayName("engine selection and refusals")
    class EngineSelection {

        @Test
        @DisplayName("the engine comes from the first declared workload")
        void engineFromFirstWorkload() {
            PerformanceTestSpecification specification = SpecificationFixtures.apiOnly();

            assertThat(compiler.compile(ExecutionPlanId.generate(), versionOf(specification), NOW)
                    .engine())
                    .isEqualTo(specification.spec().workloads().get(0).engine());
        }

        @Test
        @DisplayName("a specification with no workloads cannot be compiled")
        void noWorkloadsIsRefused() {
            PerformanceTestSpecification empty = withWorkloads(
                    SpecificationFixtures.apiOnly(), java.util.List.of());

            assertThatThrownBy(() ->
                    compiler.compile(ExecutionPlanId.generate(), versionOf(empty), NOW))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("no workloads");
        }
    }

    @Test
    @DisplayName("the plan records the specification digest it was compiled from")
    void planReferencesSpecificationDigest() {
        DefinitionVersion version = versionOf(SpecificationFixtures.apiOnly());

        assertThat(compiler.compile(ExecutionPlanId.generate(), version, NOW).planJson())
                .contains(version.digest().value())
                .contains("\"planVersion\":\"v1alpha1\"");
    }

    // ── Fixture helpers ──────────────────────────────────────────────────

    private static PerformanceTestSpecification withScenarios(
            PerformanceTestSpecification specification,
            java.util.List<PerformanceTestSpecification.ScenarioSpec> scenarios) {

        var workload = specification.spec().workloads().get(0);
        var replaced = new PerformanceTestSpecification.WorkloadSpec(
                workload.name(), workload.type(), workload.engine(), workload.sourceRef(),
                workload.model(), scenarios, workload.startWhen(), workload.browserUsers(),
                workload.iterationsPerUser());

        var workloads = new java.util.ArrayList<>(specification.spec().workloads());
        workloads.set(0, replaced);
        return withWorkloads(specification, workloads);
    }

    private static PerformanceTestSpecification withWorkloads(
            PerformanceTestSpecification specification,
            java.util.List<PerformanceTestSpecification.WorkloadSpec> workloads) {

        var spec = specification.spec();
        return new PerformanceTestSpecification(
                specification.apiVersion(), specification.kind(), specification.metadata(),
                new PerformanceTestSpecification.TestSpec(
                        spec.environment(), spec.sources(), spec.authentication(), spec.datasets(),
                        workloads, spec.objectives(), spec.observability(), spec.governance()));
    }
}
