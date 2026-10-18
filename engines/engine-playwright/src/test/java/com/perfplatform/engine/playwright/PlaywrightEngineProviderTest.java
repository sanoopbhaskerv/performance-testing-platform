package com.perfplatform.engine.playwright;

import com.perfplatform.domain.identity.ExecutionPlanId;
import com.perfplatform.domain.identity.TestDefinitionVersionId;
import com.perfplatform.domain.identity.TestRunId;
import com.perfplatform.domain.plan.ExecutionPlan;
import com.perfplatform.domain.results.SampleSet;
import com.perfplatform.domain.spec.PerformanceTestSpecification;
import com.perfplatform.domain.spec.PerformanceTestSpecification.BrowserProfile;
import com.perfplatform.domain.spec.PerformanceTestSpecification.EnvironmentSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.GovernanceSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.ObjectiveSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.ObservabilitySpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.SourceSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.SpecMetadata;
import com.perfplatform.domain.spec.PerformanceTestSpecification.TestSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.WorkloadSpec;
import com.perfplatform.domain.spec.SourceType;
import com.perfplatform.domain.spec.SpecificationDigest;
import com.perfplatform.domain.spec.TargetClass;
import com.perfplatform.domain.spec.WorkloadType;
import com.perfplatform.domain.target.TargetAddress;
import com.perfplatform.engine.spi.EnginePlan;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlaywrightEngineProviderTest {

    private final PlaywrightEngineProvider provider = new PlaywrightEngineProvider();

    @Test
    @DisplayName("capabilities declare a pinned browser runner image")
    void capabilities() {
        assertThat(provider.engineId()).isEqualTo("playwright");
        assertThat(provider.capabilities().supports(WorkloadKind.BROWSER)).isTrue();
        assertThat(provider.capabilities().requiredRunnerImage())
                .isEqualTo("perfplatform/playwright-runner:1.62.1-1");
    }

    @Test
    @DisplayName("compiled plan is deterministic and carries source digest plus browser profile")
    void compile() {
        EnginePlan first = provider.compile(specification(), "browser-experience");
        EnginePlan second = provider.compile(specification(), "browser-experience");

        assertThat(first).isEqualTo(second);
        assertThat(first.planJson())
                .contains("\"engine\":\"playwright\"")
                .contains("\"sourceDigest\":\"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"")
                .contains("\"browserUsers\":5")
                .contains("\"device\":\"mobile\"")
                .contains("\"network\":\"slow-4g\"")
                .contains("\"cpuThrottle\":4");
        assertThat(first.peakVirtualUsers()).isEqualTo(5);
        assertThat(first.shardCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("rendered shard plan injects the resolved target and correlation identity")
    void render() {
        EnginePlan compiled = provider.compile(specification(), "browser-experience");
        ExecutionPlan plan = new ExecutionPlan(
                ExecutionPlanId.generate(),
                TestDefinitionVersionId.generate(),
                "browser-experience", provider.engineId(),
                compiled.planJson(),
                SpecificationDigest.ofCanonicalForm(compiled.planJson()),
                new ExecutionPlan.CapacityEstimate(1, 5, 900),
                Instant.parse("2026-08-03T10:00:00Z"));

        RenderedWorkload rendered = provider.render(specification(), plan,
                new ShardAssignment(TestRunId.generate(), 0, 1, "X-Run"),
                TargetAddress.parse("https://proposal.example.internal"));

        assertThat(rendered.fileName()).isEqualTo("shard-0-playwright-plan.json");
        assertThat(rendered.contentType()).isEqualTo("application/json");
        assertThat(rendered.content())
                .contains("\"targetBaseUrl\":\"https://proposal.example.internal\"")
                .contains("\"correlationHeader\":\"X-Run\"")
                .contains("\"planDigest\":\"" + plan.planDigest().value() + "\"");
    }

    @Test
    @DisplayName("result CSV normalizes journey, step and web-vital rows")
    void parse() {
        String csv = """
                metric,operation,value,success,timestamp
                browser.journey.duration,browser-experience,1200,true,1753876800000
                browser.step.duration,browser-experience/sign-in,200,true,1753876800100
                web.lcp,browser-experience/home,2400,true,1753876800200
                web.cls,browser-experience/home,95,true,1753876800300
                bad,row
                """;

        SampleSet samples = provider.parse(new RunnerOutput(
                TestRunId.generate(), 1, List.of(new RunnerOutput.ShardResult(0, csv))));

        assertThat(samples.unparsedRows()).isEqualTo(1);
        assertThat(samples.samples()).extracting(sample -> sample.includeInAggregate())
                .containsExactly(true, false, false, false);
        assertThat(samples.samples()).extracting(sample -> sample.operation())
                .containsExactly(
                        "browser-experience",
                        "browser-experience/sign-in",
                        "web.lcp@browser-experience/home",
                        "web.cls@browser-experience/home");
    }

    /**
     * The defect this closes: git coordinates validated, accepted, and then dropped.
     *
     * <p>A git-pinned source used to compile into a plan whose {@code sourceUri} and
     * {@code sourceDigest} were both empty strings and which said nothing else. The runner received
     * a workload with no script to run — the silent-drop failure S9 removed for engines, arriving
     * from a different direction.
     */
    @Test
    @DisplayName("git coordinates reach the compiled plan, and therefore the plan digest")
    void compilesGitPinnedSource() {
        EnginePlan plan = provider.compile(gitPinnedSpecification(), "browser-experience");

        assertThat(plan.planJson())
                .contains("\"sourceGitRepository\":\"https://github.com/ameriprise/perf-journeys\"")
                .contains("\"sourceGitRef\":\"" + "a".repeat(40) + "\"")
                .contains("\"sourceGitPath\":\"tests/checkout.spec.ts\"")
                .contains("\"sourceGitProject\":\"chromium\"");
    }

    @Test
    @DisplayName("a source carrying neither an artifact nor git coordinates is refused, not compiled")
    void refusesSourceWithNoMaterial() {
        assertThatThrownBy(() -> provider.compile(
                specificationWithSource(new SourceSpec("proposal-browser", SourceType.PLAYWRIGHT,
                        null, null, null)),
                "browser-experience"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no Playwright test to run");
    }

    private static PerformanceTestSpecification gitPinnedSpecification() {
        return specificationWithSource(new SourceSpec("proposal-browser", SourceType.PLAYWRIGHT,
                null, null, new SourceSpec.GitSource(
                        "https://github.com/ameriprise/perf-journeys", "a".repeat(40),
                        "tests/checkout.spec.ts", "chromium")));
    }

    private static PerformanceTestSpecification specification() {
        return specificationWithSource(new SourceSpec("proposal-browser", SourceType.PLAYWRIGHT,
                "artifact://playwright-sources/proposal-journey.spec.ts",
                "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                null));
    }

    private static PerformanceTestSpecification specificationWithSource(SourceSpec source) {
        return new PerformanceTestSpecification(
                PerformanceTestSpecification.SUPPORTED_API_VERSION,
                PerformanceTestSpecification.SUPPORTED_KIND,
                new SpecMetadata("proposal-browser", "platform", "proposal-service", Map.of()),
                new TestSpec(
                        new EnvironmentSpec("performance", "standalone", TargetClass.NON_PRODUCTION),
                        List.of(source),
                        List.of(),
                        List.of(),
                        List.of(new WorkloadSpec("browser-experience", WorkloadType.BROWSER,
                                "playwright", "proposal-browser", null, null, List.of(),
                                null, 5, 2, new BrowserProfile("mobile", "slow-4g", 4))),
                        List.<ObjectiveSpec>of(),
                        new ObservabilitySpec("dynatrace", "X-Performance-Test-Run-Id",
                                "service:proposal", List.of("service.latency")),
                        new GovernanceSpec(Duration.ofMinutes(15), "none", Duration.ofDays(14))));
    }
}
