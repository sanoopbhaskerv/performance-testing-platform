package com.perfplatform.orchestration.plan;

import com.perfplatform.domain.identity.ExecutionPlanId;
import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.identity.TestDefinitionId;
import com.perfplatform.domain.identity.TestDefinitionVersionId;
import com.perfplatform.domain.plan.ExecutionPlan;
import com.perfplatform.domain.port.DefinitionVersionCatalog.DefinitionVersion;
import com.perfplatform.domain.results.SampleSet;
import com.perfplatform.domain.spec.LoadModelType;
import com.perfplatform.domain.spec.PerformanceTestSpecification;
import com.perfplatform.domain.spec.PerformanceTestSpecification.EnvironmentSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.LoadModel;
import com.perfplatform.domain.spec.PerformanceTestSpecification.LoadStage;
import com.perfplatform.domain.spec.PerformanceTestSpecification.ScenarioSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.SpecMetadata;
import com.perfplatform.domain.spec.PerformanceTestSpecification.TestSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.WorkloadSpec;
import com.perfplatform.domain.spec.SpecificationDigest;
import com.perfplatform.domain.spec.TargetClass;
import com.perfplatform.domain.spec.WorkloadType;
import com.perfplatform.domain.target.TargetAddress;
import com.perfplatform.engine.spi.EngineCapabilities;
import com.perfplatform.engine.spi.EnginePlan;
import com.perfplatform.engine.spi.ExecutionEngineProvider;
import com.perfplatform.engine.spi.RenderedWorkload;
import com.perfplatform.engine.spi.RunnerOutput;
import com.perfplatform.engine.spi.ShardAssignment;
import com.perfplatform.engine.spi.WorkloadKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EngineDispatchingPlanCompilerTest {

    @Test
    @DisplayName("single-engine specifications compile through the matching provider")
    void compilesSingleEngine() {
        EngineDispatchingPlanCompiler compiler = new EngineDispatchingPlanCompiler(
                new EngineRegistry(List.of(provider("jmeter", WorkloadKind.PROTOCOL))));

        List<ExecutionPlan> plans = compiler.compile(ExecutionPlanId::generate,
                version(singleEngineSpecification()), Instant.parse("2026-08-03T10:00:00Z"));

        assertThat(plans).singleElement().satisfies(plan -> {
            assertThat(plan.engine()).isEqualTo("jmeter");
            assertThat(plan.planJson()).contains("\"engine\":\"jmeter\"");
        });
    }

    /**
     * The change S9 made, and the defect it closes.
     *
     * <p>A mixed-engine specification used to be refused here, and before S6 its later workloads
     * were silently dropped — the run executed the first engine's workload, reported success, and
     * nothing said half the test had not run. It now compiles to one plan per workload.
     */
    @Test
    @DisplayName("a mixed-engine specification compiles to one plan per workload")
    void compilesEachWorkloadForItsOwnEngine() {
        EngineDispatchingPlanCompiler compiler = new EngineDispatchingPlanCompiler(
                new EngineRegistry(List.of(
                        provider("jmeter", WorkloadKind.PROTOCOL),
                        provider("playwright", WorkloadKind.BROWSER))));

        List<ExecutionPlan> plans = compiler.compile(ExecutionPlanId::generate,
                version(mixedEngineSpecification()), Instant.parse("2026-08-03T10:00:00Z"));

        assertThat(plans).hasSize(2);
        assertThat(plans).extracting(ExecutionPlan::engine)
                .containsExactly("jmeter", "playwright");
        assertThat(plans).extracting(ExecutionPlan::workloadName)
                .containsExactly("api-load", "browser-experience");

        // Distinct digests, so a runner's manifest identifies which plan it executed. Two plans of
        // one run that hashed the same would make that check unable to tell them apart.
        assertThat(plans.get(0).planDigest()).isNotEqualTo(plans.get(1).planDigest());
    }

    @Test
    @DisplayName("a workload naming an engine this platform lacks fails loudly")
    void rejectsAnUnregisteredEngine() {
        EngineDispatchingPlanCompiler compiler = new EngineDispatchingPlanCompiler(
                new EngineRegistry(List.of(provider("jmeter", WorkloadKind.PROTOCOL))));

        assertThatThrownBy(() -> compiler.compile(ExecutionPlanId::generate,
                version(mixedEngineSpecification()), Instant.parse("2026-08-03T10:00:00Z")))
                .isInstanceOf(EngineRegistry.UnknownEngineException.class)
                .hasMessageContaining("playwright");
    }

    private static DefinitionVersion version(PerformanceTestSpecification specification) {
        return new DefinitionVersion(
                TestDefinitionVersionId.generate(),
                TestDefinitionId.generate(),
                TeamId.generate(),
                1,
                SpecificationDigest.of(specification),
                specification,
                true);
    }

    private static PerformanceTestSpecification singleEngineSpecification() {
        return new PerformanceTestSpecification(
                PerformanceTestSpecification.SUPPORTED_API_VERSION,
                PerformanceTestSpecification.SUPPORTED_KIND,
                new SpecMetadata("api", "team", "app", Map.of()),
                new TestSpec(
                        new EnvironmentSpec("performance", "cell", TargetClass.NON_PRODUCTION),
                        List.of(), List.of(), List.of(),
                        List.of(protocolWorkload()),
                        List.of(), null, null));
    }

    private static PerformanceTestSpecification mixedEngineSpecification() {
        return new PerformanceTestSpecification(
                PerformanceTestSpecification.SUPPORTED_API_VERSION,
                PerformanceTestSpecification.SUPPORTED_KIND,
                new SpecMetadata("hybrid", "team", "app", Map.of()),
                new TestSpec(
                        new EnvironmentSpec("performance", "cell", TargetClass.NON_PRODUCTION),
                        List.of(), List.of(), List.of(),
                        List.of(protocolWorkload(), browserWorkload()),
                        List.of(), null, null));
    }

    private static WorkloadSpec protocolWorkload() {
        return new WorkloadSpec("api-load", WorkloadType.PROTOCOL, "jmeter", null,
                new LoadModel(LoadModelType.STAGES, List.of(
                        new LoadStage(Duration.ofMinutes(1), 10))),
                List.of(new ScenarioSpec("search", 100, "search")), null, null, null);
    }

    private static WorkloadSpec browserWorkload() {
        return new WorkloadSpec("browser-experience", WorkloadType.BROWSER, "playwright", null,
                null, List.of(), null, 5, 1);
    }

    private static ExecutionEngineProvider provider(String engineId, WorkloadKind kind) {
        return new ExecutionEngineProvider() {
            @Override
            public String engineId() {
                return engineId;
            }

            @Override
            public EngineCapabilities capabilities() {
                return new EngineCapabilities(engineId, Set.of(kind), Set.of("manual"),
                        Set.of("stages"), 20, false, false, "stub/%s:1.0.0".formatted(engineId));
            }

            @Override
            public com.perfplatform.domain.validation.ValidationResult validate(
                    PerformanceTestSpecification specification) {
                return com.perfplatform.domain.validation.ValidationResult.valid();
            }

            @Override
            public EnginePlan compile(PerformanceTestSpecification specification,
                    String workloadName) {
                return new EnginePlan(
                        "{\"engine\":\"" + engineId + "\",\"workload\":\"" + workloadName + "\"}",
                        1, 1, 60);
            }

            @Override
            public RenderedWorkload render(PerformanceTestSpecification specification,
                    ExecutionPlan plan, ShardAssignment assignment, TargetAddress target) {
                return new RenderedWorkload("plan.json", "application/json", plan.planJson());
            }

            @Override
            public SampleSet parse(RunnerOutput output) {
                return SampleSet.empty();
            }
        };
    }
}
