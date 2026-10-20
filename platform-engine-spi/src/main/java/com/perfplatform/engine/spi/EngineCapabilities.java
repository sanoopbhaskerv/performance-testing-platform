package com.perfplatform.engine.spi;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * What an engine can do, declared rather than inferred.
 *
 * <p>Validation and admission control read these capabilities so they can reason about a
 * workload without knowing which engine will run it. Without a declarative contract, every new
 * engine forces a branch into orchestration — the coupling this SPI exists to prevent.
 *
 * @param engineId                      stable identifier, e.g. {@code jmeter}, {@code playwright}
 * @param workloadKinds                 which classes of workload this engine produces
 * @param supportedSourceTypes          import source types this engine can compile, e.g.
 *                                      {@code openapi}, {@code postman}, {@code har}
 * @param supportedWorkloadModels       load models this engine understands, e.g. {@code stages}
 * @param maxConfiguredConcurrency      the platform-configured ceiling for one shard of this
 *                                      engine. A policy limit, not a benchmark result: the real
 *                                      figure comes from runner calibration
 * @param supportsCorrelationExtraction whether the engine can extract values from one response
 *                                      and correlate them into a later request
 * @param supportsShardedExecution      whether a workload can be split across autonomous shards
 * @param requiredRunnerImage           the pinned runner image this engine executes in
 * @see <a href="../../../../../../../docs/adr/003-engine-spi.md">ADR 003</a>
 */
public record EngineCapabilities(
        String engineId,
        Set<WorkloadKind> workloadKinds,
        Set<String> supportedSourceTypes,
        Set<String> supportedWorkloadModels,
        int maxConfiguredConcurrency,
        boolean supportsCorrelationExtraction,
        boolean supportsShardedExecution,
        String requiredRunnerImage) {

    public EngineCapabilities {
        engineId = requireText(engineId, "engineId");
        workloadKinds = immutableCopy(workloadKinds, "workloadKinds");
        supportedSourceTypes = immutableCopy(supportedSourceTypes, "supportedSourceTypes");
        supportedWorkloadModels = immutableCopy(supportedWorkloadModels, "supportedWorkloadModels");

        if (workloadKinds.isEmpty()) {
            throw new IllegalArgumentException("workloadKinds must declare at least one kind");
        }
        if (maxConfiguredConcurrency <= 0) {
            throw new IllegalArgumentException("maxConfiguredConcurrency must be positive");
        }
        requiredRunnerImage = requirePinnedImage(requiredRunnerImage);
    }

    /** True if this engine can serve the given workload kind. */
    public boolean supports(WorkloadKind kind) {
        return workloadKinds.contains(kind);
    }

    /**
     * Runner images must be pinned. An unpinned or {@code latest} tag means two shards of the
     * same run can execute different engine builds, which silently invalidates the results and
     * makes a run impossible to reproduce from its stored plan digest.
     */
    private static String requirePinnedImage(String image) {
        String value = requireText(image, "requiredRunnerImage");
        int tagSeparator = value.lastIndexOf(':');
        int lastSlash = value.lastIndexOf('/');
        boolean hasTag = tagSeparator > lastSlash;
        if (!hasTag) {
            throw new IllegalArgumentException(
                    "requiredRunnerImage must be pinned to an explicit tag or digest");
        }
        if (value.endsWith(":latest")) {
            throw new IllegalArgumentException(
                    "requiredRunnerImage must not use the 'latest' tag");
        }
        return value;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be null or blank");
        }
        return value.trim();
    }

    private static <T> Set<T> immutableCopy(Set<T> values, String field) {
        if (values == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(values));
    }
}
