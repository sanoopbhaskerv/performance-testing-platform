package com.perfplatform.domain.validation;

import com.perfplatform.domain.spec.PerformanceTestSpecification;
import com.perfplatform.domain.spec.PerformanceTestSpecification.EnvironmentSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.LoadModel;
import com.perfplatform.domain.spec.PerformanceTestSpecification.LoadStage;
import com.perfplatform.domain.spec.PerformanceTestSpecification.ScenarioSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.SpecMetadata;
import com.perfplatform.domain.spec.PerformanceTestSpecification.TestSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.WorkloadSpec;
import com.perfplatform.domain.spec.LoadModelType;
import com.perfplatform.domain.spec.TargetClass;
import com.perfplatform.domain.spec.WorkloadType;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** The smallest valid specification that carries one scenario, so a test can vary only that. */
final class SpecificationFixturesForJourneys {

    private SpecificationFixturesForJourneys() {
    }

    static PerformanceTestSpecification with(ScenarioSpec scenario) {
        return new PerformanceTestSpecification(
                PerformanceTestSpecification.SUPPORTED_API_VERSION,
                PerformanceTestSpecification.SUPPORTED_KIND,
                new SpecMetadata("journey-test", "local-dev", "demo-service", Map.of()),
                new TestSpec(
                        new EnvironmentSpec("performance", "local", TargetClass.NON_PRODUCTION),
                        List.of(), List.of(), List.of(),
                        List.of(new WorkloadSpec("load", WorkloadType.PROTOCOL, "jmeter", null,
                                new LoadModel(LoadModelType.STAGES,
                                        List.of(new LoadStage(Duration.ofSeconds(30), 5))),
                                List.of(scenario), null, null, null)),
                        List.of(), null, null));
    }
}
