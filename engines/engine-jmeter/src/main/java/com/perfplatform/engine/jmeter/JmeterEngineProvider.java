package com.perfplatform.engine.jmeter;

import com.perfplatform.engine.spi.EngineCapabilities;
import com.perfplatform.engine.spi.ExecutionEngineProvider;
import com.perfplatform.engine.spi.WorkloadKind;

import java.util.Set;

/**
 * JMeter provider for the engine SPI.
 *
 * <p>Increment 1A declares JMeter's identity and capabilities so orchestration can reason about
 * it through the SPI. Plan compilation, sharding and result normalization move here from
 * {@code JmeterTestOrchestrator} in Increment 1D, at which point the compiler defects recorded in
 * {@code docs/migration/baseline-report.md} (numeric corruption at values of 1,000 or more, the
 * missing spike template, endurance reusing load scenarios, and scenario flattening) are fixed.
 *
 * <p>This class holds no Spring annotations. It is registered as a bean by the composition root
 * in {@code platform-core}, keeping the engine module free of the framework.
 */
public final class JmeterEngineProvider implements ExecutionEngineProvider {

    /** Stable identifier persisted on execution plans and runner shards. */
    public static final String ENGINE_ID = "jmeter";

    /**
     * Pinned runner image. Never {@code latest}: two shards of one run must execute the same
     * engine build, or the merged result is not reproducible from the stored plan digest.
     * The registry prefix becomes an approved internal registry before production use.
     */
    private static final String RUNNER_IMAGE = "justb4/jmeter:5.6.2";

    /**
     * Per-shard ceiling. This is a policy limit, not a measured one. The real figure comes from
     * runner calibration against a representative workload; virtual-user count alone is not a
     * reliable CPU-sizing measure. Until calibration is complete this bounds a single shard well
     * below the 1,000 virtual-user per-run maximum, so a run at the ceiling shards rather than
     * overloading one generator.
     */
    private static final int MAX_CONFIGURED_CONCURRENCY_PER_SHARD = 500;

    private static final EngineCapabilities CAPABILITIES = new EngineCapabilities(
            ENGINE_ID,
            Set.of(WorkloadKind.PROTOCOL),
            Set.of("openapi", "postman", "har", "curl", "manual"),
            Set.of("stages"),
            MAX_CONFIGURED_CONCURRENCY_PER_SHARD,
            true,
            true,
            RUNNER_IMAGE);

    @Override
    public String engineId() {
        return ENGINE_ID;
    }

    @Override
    public EngineCapabilities capabilities() {
        return CAPABILITIES;
    }
}
