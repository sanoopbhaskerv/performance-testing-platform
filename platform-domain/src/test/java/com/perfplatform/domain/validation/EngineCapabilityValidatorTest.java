package com.perfplatform.domain.validation;

import com.perfplatform.domain.spec.LoadModelType;
import com.perfplatform.domain.spec.PerformanceTestSpecification;
import com.perfplatform.domain.spec.PerformanceTestSpecification.LoadModel;
import com.perfplatform.domain.spec.PerformanceTestSpecification.LoadStage;
import com.perfplatform.domain.spec.PerformanceTestSpecification.ScenarioSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.WorkloadSpec;
import com.perfplatform.domain.spec.WorkloadType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Can this deployment execute what the document names?
 *
 * <p>The test this file exists for is {@link #refusesASecondWorkloadWhoseEngineIsMissing()}. Before
 * S6, a specification declaring two engines had its later workloads <b>silently dropped</b>: the run
 * executed the first workload's engine, reported success, and nothing anywhere said that half the
 * test had not run. S6 stopped that by refusing at compile time — inside the reconciler, as a failed
 * run, minutes after the trigger and long after the definition version had been stored as immutable.
 *
 * <p>This layer moves the refusal to the moment somebody asks whether their document is valid.
 */
class EngineCapabilityValidatorTest {

    private static final Set<String> INSTALLED = Set.of("jmeter");

    private static WorkloadSpec workload(String name, String engine) {
        return new WorkloadSpec(name, WorkloadType.PROTOCOL, engine, null, null,
                new LoadModel(LoadModelType.STAGES,
                        List.of(new LoadStage(Duration.ofMinutes(1), 10))),
                List.of(new ScenarioSpec("browse", 100, "browse")), null, null, null);
    }

    private static PerformanceTestSpecification specificationWith(WorkloadSpec... workloads) {
        return new PerformanceTestSpecification(
                PerformanceTestSpecification.SUPPORTED_API_VERSION,
                PerformanceTestSpecification.SUPPORTED_KIND,
                new PerformanceTestSpecification.SpecMetadata("t", "team", "app", null),
                new PerformanceTestSpecification.TestSpec(
                        new PerformanceTestSpecification.EnvironmentSpec(
                                "performance", "local",
                                com.perfplatform.domain.spec.TargetClass.NON_PRODUCTION),
                        List.of(), List.of(), List.of(), List.of(workloads),
                        List.of(), null, null));
    }

    /**
     * The acceptance criterion for S9.
     */
    @Test
    @DisplayName("a second workload naming an engine this platform lacks is refused")
    void refusesASecondWorkloadWhoseEngineIsMissing() {
        var specification = specificationWith(
                workload("api-load", "jmeter"),
                workload("browser-journey", "playwright"));

        var findings = EngineCapabilityValidator.validate(specification, INSTALLED);

        assertThat(findings.codes()).contains("spec.workload.engine.unavailable");
        assertThat(findings.errors())
                .singleElement()
                .satisfies(finding -> {
                    // Named, and pointed at. A finding that said "an engine is missing" without
                    // saying which workload would send somebody to read all of them.
                    assertThat(finding.message()).contains("playwright").contains("browser-journey");
                    assertThat(finding.path()).isEqualTo("/spec/workloads/1/engine");
                    assertThat(finding.remediation()).contains("jmeter");
                });
    }

    @Test
    @DisplayName("a workload naming an installed engine passes")
    void acceptsAnInstalledEngine() {
        var findings = EngineCapabilityValidator.validate(
                specificationWith(workload("api-load", "jmeter")), INSTALLED);

        assertThat(findings.findings()).isEmpty();
    }

    @Test
    @DisplayName("engine identifiers are matched without regard to case or padding")
    void matchesLoosely() {
        var findings = EngineCapabilityValidator.validate(
                specificationWith(workload("api-load", "  JMeter ")), INSTALLED);

        assertThat(findings.findings()).isEmpty();
    }

    /**
     * The semantic layer already reports a workload with no engine. Two findings for one mistake
     * teaches a consumer that the platform is noisy rather than that their document is wrong.
     */
    @Test
    @DisplayName("a workload with no engine is left to the semantic layer")
    void staysQuietWhenTheEngineIsAbsent() {
        var findings = EngineCapabilityValidator.validate(
                specificationWith(workload("api-load", null)), INSTALLED);

        assertThat(findings.findings()).isEmpty();
    }

    @Test
    @DisplayName("a platform with no engines at all says so, rather than blaming the document")
    void reportsAnEmptyPlatformAsAPlatformProblem() {
        var findings = EngineCapabilityValidator.validate(
                specificationWith(workload("api-load", "jmeter")), Set.of());

        assertThat(findings.errors())
                .singleElement()
                .satisfies(finding -> assertThat(finding.remediation())
                        .contains("platform configuration problem"));
    }

    @Test
    @DisplayName("a document with no body is not this layer's problem")
    void ignoresAnEmptyDocument() {
        assertThat(EngineCapabilityValidator.validate(null, INSTALLED).findings()).isEmpty();
    }
}
