package com.perfplatform.engine.jmeter;

import com.perfplatform.engine.spi.EngineCapabilities;
import com.perfplatform.engine.spi.ExecutionEngineProvider;
import com.perfplatform.engine.spi.WorkloadKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JmeterEngineProviderTest {

    private final ExecutionEngineProvider provider = new JmeterEngineProvider();

    @Test
    @DisplayName("the engine id is stable, because it is persisted on plans and shards")
    void exposesStableEngineId() {
        assertThat(provider.engineId()).isEqualTo("jmeter");
    }

    @Test
    @DisplayName("JMeter declares itself a protocol engine, never a browser engine")
    void declaresProtocolWorkloadOnly() {
        EngineCapabilities capabilities = provider.capabilities();

        assertThat(capabilities.supports(WorkloadKind.PROTOCOL)).isTrue();
        assertThat(capabilities.supports(WorkloadKind.BROWSER)).isFalse();
    }

    @Test
    @DisplayName("capabilities are stable across calls, so admission decisions are reproducible")
    void capabilitiesAreStable() {
        assertThat(provider.capabilities()).isEqualTo(provider.capabilities());
    }

    @Test
    @DisplayName("the declared runner image is pinned to an explicit version")
    void pinsRunnerImage() {
        String image = provider.capabilities().requiredRunnerImage();

        assertThat(image).doesNotEndWith(":latest");
        assertThat(image).matches(".+[:@].+");
    }

    /**
     * The per-shard ceiling must sit below the platform's 1,000 virtual-user per-run maximum, so
     * a run at that maximum is sharded rather than concentrated on a single load generator. This
     * is a policy bound; the real figure comes from runner calibration.
     */
    @Test
    @DisplayName("the per-shard concurrency ceiling is below the per-run virtual-user maximum")
    void shardCeilingForcesShardingAtMaximumLoad() {
        int maximumVirtualUsersPerRun = 1_000;

        assertThat(provider.capabilities().maxConfiguredConcurrency())
                .isPositive()
                .isLessThan(maximumVirtualUsersPerRun);
    }

    @Test
    @DisplayName("JMeter declares sharded execution and correlation support")
    void declaresShardingAndCorrelation() {
        EngineCapabilities capabilities = provider.capabilities();

        assertThat(capabilities.supportsShardedExecution()).isTrue();
        assertThat(capabilities.supportsCorrelationExtraction()).isTrue();
    }

    @Test
    @DisplayName("the source types JMeter can compile are declared for the importers to check against")
    void declaresSupportedSourceTypes() {
        assertThat(provider.capabilities().supportedSourceTypes())
                .contains("openapi", "postman", "har", "curl", "manual")
                .doesNotContain("playwright");
    }
}
