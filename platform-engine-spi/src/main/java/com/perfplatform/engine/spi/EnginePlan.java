package com.perfplatform.engine.spi;

import java.util.Objects;

/**
 * What an engine compiles a specification into.
 *
 * <p>The engine's contribution to an {@link com.perfplatform.domain.plan.ExecutionPlan}: the plan
 * body and the capacity it will demand. The control plane wraps this with identity, a digest and a
 * creation time — the parts that are the platform's business rather than the engine's.
 *
 * <h2>Why this is not the runner's input file</h2>
 * {@code planJson} is a structured description of what will run, not a JMX, a k6 script or anything
 * else an engine executes directly. Those are <em>rendered</em> from it, per shard, at provisioning
 * time ({@link ExecutionEngineProvider#render}). Keeping them apart matters for three reasons:
 *
 * <ul>
 *   <li>the plan is read on every reconciliation pass to compute deadlines, and a row carrying
 *       hundreds of kilobytes of XML is the wrong thing to read that often;</li>
 *   <li>one plan produces a different file per shard, because each shard takes a different slice of
 *       the dataset — the plan cannot hold all of them;</li>
 *   <li>the plan is what a person reviews and approves. A reviewer can read this; nobody reviews
 *       generated JMX.</li>
 * </ul>
 *
 * @param planJson         deterministic JSON describing the compiled workload. The same
 *                         specification and profile must always produce identical bytes: its digest
 *                         is the run's identity and is what runner results are verified against
 * @param shardCount       how many runners this plan needs
 * @param peakVirtualUsers highest concurrent virtual users across all stages
 * @param durationSeconds  total wall-clock time the stages describe
 */
public record EnginePlan(
        String planJson,
        int shardCount,
        int peakVirtualUsers,
        long durationSeconds) {

    public EnginePlan {
        Objects.requireNonNull(planJson, "planJson");
        if (planJson.isBlank()) {
            throw new IllegalArgumentException("planJson must not be blank");
        }
        if (shardCount < 1) {
            throw new IllegalArgumentException("shardCount must be at least 1");
        }
        if (peakVirtualUsers < 0) {
            throw new IllegalArgumentException("peakVirtualUsers must not be negative");
        }
        if (durationSeconds < 0) {
            throw new IllegalArgumentException("durationSeconds must not be negative");
        }
    }
}
