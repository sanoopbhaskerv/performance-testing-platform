package com.perfplatform.domain.spec;

import com.perfplatform.domain.spec.PerformanceTestSpecification.AuthenticationSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.DatasetSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.EnvironmentSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.GovernanceSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.LoadModel;
import com.perfplatform.domain.spec.PerformanceTestSpecification.LoadStage;
import com.perfplatform.domain.spec.PerformanceTestSpecification.ObjectiveSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.ObservabilitySpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.ScenarioSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.SourceSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.SpecMetadata;
import com.perfplatform.domain.spec.PerformanceTestSpecification.TestSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.WorkloadSpec;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Builders for valid specifications, so each test states only the field it is about.
 *
 * <p>Every fixture here is valid on purpose. A test that needs an invalid specification derives
 * it from one of these with a single deliberate change, which keeps it obvious what is being
 * tested and stops fixtures from drifting into accidental invalidity.
 */
public final class SpecificationFixtures {

    private SpecificationFixtures() {
    }

    /** A protocol-only API load test: the common case. */
    public static PerformanceTestSpecification apiOnly() {
        return new PerformanceTestSpecification(
                PerformanceTestSpecification.SUPPORTED_API_VERSION,
                PerformanceTestSpecification.SUPPORTED_KIND,
                new SpecMetadata("proposal-workflow-baseline", "advice-implementation", "proposal",
                        Map.of("tier", "tier-0", "portfolio", "awm")),
                new TestSpec(
                        new EnvironmentSpec("performance", "advice-perf-eks", TargetClass.NON_PRODUCTION),
                        List.of(new SourceSpec("proposal-api", SourceType.OPENAPI,
                                "artifact://proposal/openapi.yaml", null)),
                        List.of(new AuthenticationSpec("test-user", "oauth2-client-credentials",
                                "vault://performance/proposal/client")),
                        List.of(new DatasetSpec("client-data",
                                new DatasetSpec.DataSource("object-storage",
                                        "dataset://proposal-clients/1"),
                                true)),
                        List.of(apiWorkload()),
                        List.of(
                                new ObjectiveSpec("http.response.time", "api-background-load", 95,
                                        Threshold.parse("750ms")),
                                new ObjectiveSpec("http.error.rate", "api-background-load", null,
                                        Threshold.parse("1%"))),
                        new ObservabilitySpec("dynatrace", "X-Performance-Test-Run-Id",
                                "application:proposal",
                                List.of("service-response-time", "failure-rate", "cpu")),
                        new GovernanceSpec(Duration.ofMinutes(45), "non-production-standard",
                                Duration.ofDays(90))));
    }

    /** A browser-only journey test importing an existing Playwright spec. */
    public static PerformanceTestSpecification browserOnly() {
        return new PerformanceTestSpecification(
                PerformanceTestSpecification.SUPPORTED_API_VERSION,
                PerformanceTestSpecification.SUPPORTED_KIND,
                new SpecMetadata("proposal-journey", "advice-implementation", "proposal", Map.of()),
                new TestSpec(
                        new EnvironmentSpec("performance", "advice-perf-eks", TargetClass.NON_PRODUCTION),
                        List.of(playwrightSource()),
                        List.of(),
                        List.of(),
                        List.of(browserWorkload(null)),
                        List.of(new ObjectiveSpec("browser.journey.duration", "browser-experience", 95,
                                Threshold.parse("8s"))),
                        null,
                        new GovernanceSpec(Duration.ofMinutes(30), "non-production-standard",
                                Duration.ofDays(90))));
    }

    /** A hybrid test: a browser cohort measured during protocol steady state. */
    public static PerformanceTestSpecification hybrid() {
        return new PerformanceTestSpecification(
                PerformanceTestSpecification.SUPPORTED_API_VERSION,
                PerformanceTestSpecification.SUPPORTED_KIND,
                new SpecMetadata("proposal-hybrid", "advice-implementation", "proposal", Map.of()),
                new TestSpec(
                        new EnvironmentSpec("performance", "advice-perf-eks", TargetClass.NON_PRODUCTION),
                        List.of(
                                new SourceSpec("proposal-api", SourceType.OPENAPI,
                                        "artifact://proposal/openapi.yaml", null),
                                playwrightSource()),
                        List.of(new AuthenticationSpec("test-user", "oauth2-client-credentials",
                                "vault://performance/proposal/client")),
                        List.of(),
                        List.of(
                                apiWorkload(),
                                browserWorkload(new PerformanceTestSpecification.StartCondition(
                                        "api-background-load", "steady-state"))),
                        List.of(
                                new ObjectiveSpec("http.response.time", "api-background-load", 95,
                                        Threshold.parse("750ms")),
                                new ObjectiveSpec("browser.journey.duration", "browser-experience", 95,
                                        Threshold.parse("8s"))),
                        new ObservabilitySpec("dynatrace", "X-Performance-Test-Run-Id",
                                "application:proposal", List.of("service-response-time")),
                        new GovernanceSpec(Duration.ofMinutes(45), "non-production-standard",
                                Duration.ofDays(90))));
    }

    public static WorkloadSpec apiWorkload() {
        return new WorkloadSpec(
                "api-background-load",
                WorkloadType.PROTOCOL,
                "jmeter",
                "proposal-api",
                new LoadModel(LoadModelType.STAGES, List.of(
                        new LoadStage("ramp-up", Duration.ofMinutes(5), 200, null),
                        new LoadStage("steady-state", Duration.ofMinutes(20), 600, null),
                        new LoadStage("ramp-down", Duration.ofMinutes(5), 0, null))),
                List.of(
                        new ScenarioSpec("search-client", 60, "searchClient"),
                        new ScenarioSpec("open-proposal", 30, "getProposal"),
                        new ScenarioSpec("save-proposal", 10, "saveProposal")),
                null,
                null,
                null);
    }

    public static WorkloadSpec browserWorkload(PerformanceTestSpecification.StartCondition startWhen) {
        return new WorkloadSpec(
                "browser-experience",
                WorkloadType.BROWSER,
                "playwright",
                "proposal-browser",
                null,
                List.of(),
                startWhen,
                5,
                3);
    }

    public static SourceSpec playwrightSource() {
        return new SourceSpec("proposal-browser", SourceType.PLAYWRIGHT, null,
                new SourceSpec.GitSource("ssh://bitbucket/proposal-e2e.git",
                        "8f121c7a9d4e2b6c1f0a3e5d7c9b8a4f6e2d1c0b",
                        "tests/performance/proposal-journey.spec.ts", null));
    }
}
