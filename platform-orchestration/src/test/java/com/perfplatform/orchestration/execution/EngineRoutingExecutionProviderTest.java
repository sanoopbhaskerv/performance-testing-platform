package com.perfplatform.orchestration.execution;

import com.perfplatform.domain.execution.ExecutionPhase;
import com.perfplatform.domain.execution.ExecutionProvider;
import com.perfplatform.domain.execution.ProvisionRequest;
import com.perfplatform.domain.identity.ExecutionPlanId;
import com.perfplatform.domain.identity.RunnerShardId;
import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.identity.TestRunId;
import com.perfplatform.domain.run.RunnerShard.ExecutionHandle;
import com.perfplatform.domain.spec.SpecificationDigest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the router must get right for a hybrid run.
 *
 * <p>The behaviour under test is not "delegation works" but the two ways delegation can be wrong in
 * a way that costs a real run: a browser shard reaching the protocol adapter, and a handle being
 * inspected by an adapter that never started it.
 */
class EngineRoutingExecutionProviderTest {

    /** Records what it was asked to do, and reports whatever it is told to. */
    private static final class RecordingProvider implements ExecutionProvider {
        private final String id;
        private final boolean realLoad;
        private final List<String> provisioned = new ArrayList<>();
        private final List<ExecutionHandle> inspected = new ArrayList<>();

        RecordingProvider(String id, boolean realLoad) {
            this.id = id;
            this.realLoad = realLoad;
        }

        @Override
        public String providerId() {
            return id;
        }

        @Override
        public boolean generatesRealLoad() {
            return realLoad;
        }

        @Override
        public ProvisioningResult provision(ProvisionRequest request) {
            provisioned.add(request.engine());
            return ProvisioningResult.accepted(new ExecutionHandle(id, "job-" + id, "uid-" + id));
        }

        @Override
        public ExecutionStatus inspect(ExecutionHandle handle) {
            inspected.add(handle);
            return ExecutionStatus.of(ExecutionPhase.RUNNING);
        }

        @Override
        public CancellationResult cancel(ExecutionHandle handle) {
            return CancellationResult.terminated("stopped by " + id);
        }
    }

    private static ProvisionRequest requestFor(String engine) {
        return new ProvisionRequest(
                TestRunId.generate(), TeamId.generate(), RunnerShardId.generate(), 0, 1, engine,
                ExecutionPlanId.generate(), SpecificationDigest.ofCanonicalForm("{}"),
                "teams/t/runs/r/plan.json", List.of(), List.of(), Duration.ofMinutes(5));
    }

    @Test
    void sendsEachShardToTheProviderForItsEngine() {
        RecordingProvider jmeter = new RecordingProvider("local-jmeter", true);
        RecordingProvider playwright = new RecordingProvider("local-playwright", true);
        var router = new EngineRoutingExecutionProvider(
                Map.of("jmeter", jmeter, "playwright", playwright));

        router.provision(requestFor("jmeter"));
        router.provision(requestFor("playwright"));

        assertThat(jmeter.provisioned).containsExactly("jmeter");
        assertThat(playwright.provisioned).containsExactly("playwright");
    }

    @Test
    void matchesEngineNamesRegardlessOfCase() {
        RecordingProvider playwright = new RecordingProvider("local-playwright", true);
        var router = new EngineRoutingExecutionProvider(Map.of("playwright", playwright));

        assertThat(router.provision(requestFor("Playwright")).isAccepted()).isTrue();
    }

    @Test
    void refusesAnEngineNoProviderRunsAndNamesTheOnesItHas() {
        var router = new EngineRoutingExecutionProvider(
                Map.of("jmeter", new RecordingProvider("local-jmeter", true)));

        var result = router.provision(requestFor("playwright"));

        assertThat(result.isAccepted()).isFalse();
        assertThat(result.failureReason()).get(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .contains("playwright")
                .contains("jmeter");
    }

    @Test
    void inspectsAHandleWithTheProviderThatCreatedIt() {
        RecordingProvider jmeter = new RecordingProvider("local-jmeter", true);
        RecordingProvider playwright = new RecordingProvider("local-playwright", true);
        var router = new EngineRoutingExecutionProvider(
                Map.of("jmeter", jmeter, "playwright", playwright));

        var handle = router.provision(requestFor("playwright")).handle().orElseThrow();
        router.inspect(handle);

        assertThat(playwright.inspected).containsExactly(handle);
        assertThat(jmeter.inspected).isEmpty();
    }

    /**
     * A handle from an adapter this deployment no longer configures is unknown, not finished.
     *
     * <p>Reporting a terminal phase would release the run's capacity slot while a browser cohort it
     * cannot see might still be applying load.
     */
    @Test
    void reportsUnknownForAHandleWhoseProviderIsGone() {
        var router = new EngineRoutingExecutionProvider(
                Map.of("jmeter", new RecordingProvider("local-jmeter", true)));

        var orphan = new ExecutionHandle("local-playwright", "job-x", "uid-x");

        assertThat(router.inspect(orphan).phase()).isEqualTo(ExecutionPhase.UNKNOWN);
        assertThat(router.cancel(orphan).terminated()).isFalse();
    }

    @Test
    void generatesRealLoadOnlyWhenEveryRoutedProviderDoes() {
        var allReal = new EngineRoutingExecutionProvider(Map.of(
                "jmeter", new RecordingProvider("local-jmeter", true),
                "playwright", new RecordingProvider("local-playwright", true)));
        var oneFake = new EngineRoutingExecutionProvider(Map.of(
                "jmeter", new RecordingProvider("local-jmeter", true),
                "playwright", new RecordingProvider("fake", false)));

        assertThat(allReal.generatesRealLoad()).isTrue();
        assertThat(oneFake.generatesRealLoad()).isFalse();
    }
}
