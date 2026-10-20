package com.perfplatform.domain.spec;

import com.perfplatform.domain.spec.PerformanceTestSpecification.LoadModel;
import com.perfplatform.domain.spec.PerformanceTestSpecification.LoadStage;
import com.perfplatform.domain.spec.PerformanceTestSpecification.WorkloadSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two load shapes, and the one number admission control is allowed to read.
 *
 * <p>The test that matters most here is {@link Admission#budgetsAgainstTheCeilingNotTheRate()}. A
 * rate says nothing about how much of a generator a test occupies — 500 requests a second against a
 * 20 ms endpoint is about ten concurrent requests, and against a 2 s endpoint it is a thousand — so
 * a platform that budgeted against the rate would let a heavy test through as if it were free and
 * hold a light one back as if it were enormous.
 */
class LoadModelTest {

    private static WorkloadSpec protocol(LoadModel model) {
        return new WorkloadSpec("checkout", WorkloadType.PROTOCOL, "jmeter", null, null,
                model, List.of(), null, null, null, null);
    }

    private static LoadStage users(int seconds, int targetUsers) {
        return new LoadStage(Duration.ofSeconds(seconds), targetUsers);
    }

    private static LoadStage rate(int seconds, int targetRate) {
        return new LoadStage(Duration.ofSeconds(seconds), 0, targetRate);
    }

    @Nested
    @DisplayName("admission budgeting")
    class Admission {

        @Test
        @DisplayName("a closed model budgets against its peak user count")
        void closedModelBudgetsUsers() {
            WorkloadSpec workload = protocol(new LoadModel(LoadModelType.STAGES,
                    List.of(users(60, 50), users(300, 200), users(30, 0))));

            assertThat(workload.peakUsers()).isEqualTo(200);
            assertThat(workload.peakArrivalRate()).isZero();
        }

        @Test
        @DisplayName("an open model budgets against the ceiling, never against the rate")
        void budgetsAgainstTheCeilingNotTheRate() {
            WorkloadSpec workload = protocol(new LoadModel(LoadModelType.ARRIVAL_RATE,
                    List.of(rate(60, 100), rate(300, 400), rate(30, 0)), 250));

            // 400 requests a second, but at most 250 of them in flight at once. The second number
            // is the one that describes what this run occupies.
            assertThat(workload.peakUsers()).isEqualTo(250);
            assertThat(workload.peakArrivalRate()).isEqualTo(400);
        }

        @Test
        @DisplayName("an open model with no ceiling reports zero rather than guessing one")
        void noCeilingIsZero() {
            WorkloadSpec workload = protocol(new LoadModel(LoadModelType.ARRIVAL_RATE,
                    List.of(rate(60, 400)), null));

            // The validator refuses such a specification outright, so this is only ever reached
            // while validating a document that is already going to be rejected. Inventing a ceiling
            // here would be the platform deciding how much load to run on somebody's behalf.
            assertThat(workload.peakUsers()).isZero();
        }
    }

    @Nested
    @DisplayName("stage targets")
    class Targets {

        @Test
        @DisplayName("a stage reports the quantity its own model reads")
        void targetDependsOnTheModel() {
            LoadStage stage = new LoadStage(Duration.ofMinutes(1), 50, 400);

            assertThat(stage.target(LoadModelType.STAGES)).isEqualTo(50);
            assertThat(stage.target(LoadModelType.ARRIVAL_RATE)).isEqualTo(400);
        }

        @Test
        @DisplayName("an open stage with no rate reads as zero, which is ramp-down")
        void missingRateIsZero() {
            assertThat(new LoadStage(Duration.ofMinutes(1), 0, null)
                    .target(LoadModelType.ARRIVAL_RATE)).isZero();
        }

        @Test
        @DisplayName("the shape that predates the open model still constructs")
        void legacyConstructor() {
            LoadStage stage = new LoadStage(Duration.ofMinutes(1), 25);

            assertThat(stage.targetUsers()).isEqualTo(25);
            assertThat(stage.targetRate()).isNull();
        }
    }

    @Nested
    @DisplayName("the type")
    class Types {

        @Test
        @DisplayName("only arrival-rate applies load regardless of what the system does with it")
        void onlyArrivalRateIsOpen() {
            assertThat(LoadModelType.ARRIVAL_RATE.isOpen()).isTrue();
            assertThat(LoadModelType.STAGES.isOpen()).isFalse();
        }

        @Test
        @DisplayName("it is authored hyphenated, as YAML reads best")
        void parsesAuthoredForm() {
            assertThat(LoadModelType.from("arrival-rate")).isEqualTo(LoadModelType.ARRIVAL_RATE);
            assertThat(LoadModelType.from("ARRIVAL_RATE")).isEqualTo(LoadModelType.ARRIVAL_RATE);
            assertThat(LoadModelType.from("stages")).isEqualTo(LoadModelType.STAGES);
        }
    }

    @Nested
    @DisplayName("the canonical form")
    class Canonicalization {

        /**
         * The property that let the open model be added without a schema version bump.
         *
         * <p>Every definition version already stored carries a digest taken over the canonical form.
         * If adding these fields changed that form for a closed-model document, every stored digest
         * would stop verifying — and a digest that cannot be recomputed is not evidence of anything.
         */
        @Test
        @DisplayName("adding the open model left every closed-model document's digest unchanged")
        void closedModelDigestsAreUntouched() {
            PerformanceTestSpecification specification = specificationWith(
                    new LoadModel(LoadModelType.STAGES, List.of(users(60, 50))));

            String canonical = SpecificationCanonicalizer.canonicalize(specification);

            assertThat(canonical).doesNotContain("maxConcurrency");
            assertThat(canonical).doesNotContain("targetRate");
        }

        @Test
        @DisplayName("an open model carries its ceiling and rates into the digest")
        void openModelCarriesItsFields() {
            PerformanceTestSpecification specification = specificationWith(
                    new LoadModel(LoadModelType.ARRIVAL_RATE, List.of(rate(60, 400)), 250));

            String canonical = SpecificationCanonicalizer.canonicalize(specification);

            // Both, because both change what the test is. Two runs at the same rate through
            // different ceilings are different tests, and the lower-ceilinged one may never have
            // reached the rate at all.
            assertThat(canonical).contains("model.maxConcurrency=250");
            assertThat(canonical).contains("targetRate=400");
        }

        private PerformanceTestSpecification specificationWith(LoadModel model) {
            return new PerformanceTestSpecification(
                    "performance.ameriprise.com/v1alpha1", "PerformanceTest",
                    new PerformanceTestSpecification.SpecMetadata("t", "team", "app", null),
                    new PerformanceTestSpecification.TestSpec(
                            new PerformanceTestSpecification.EnvironmentSpec(
                                    "performance", "local", TargetClass.NON_PRODUCTION),
                            List.of(), List.of(), List.of(),
                            List.of(protocol(model)),
                            List.of(), null, null));
        }
    }
}
