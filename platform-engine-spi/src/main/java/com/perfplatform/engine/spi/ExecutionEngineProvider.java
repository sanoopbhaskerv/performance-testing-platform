package com.perfplatform.engine.spi;

/**
 * The contract every execution engine implements.
 *
 * <p>Orchestration talks to this interface and never to an engine directly, so a new engine can
 * be added without editing orchestration, persistence or the API.
 *
 * <h2>What an implementation must not do</h2>
 * An engine provider must never:
 * <ul>
 *   <li>authorize a caller or select a team;</li>
 *   <li>override global or per-team quotas;</li>
 *   <li>mutate run state in the database;</li>
 *   <li>return local filesystem paths as durable artifact references.</li>
 * </ul>
 * These are control-plane responsibilities. An engine able to do any of them could bypass the
 * safety controls that stop a load-generation platform from behaving like a denial-of-service
 * tool. This module's dependencies are restricted to {@code platform-domain} so that an engine
 * has no access to persistence, security or the API to begin with.
 *
 * <h2>Increment status</h2>
 * Increment 1A establishes engine identity and declared capabilities, which is what admission
 * control and validation need first. The remaining operations are added with the code that uses
 * them, so the interface never carries methods no caller invokes:
 * <ul>
 *   <li>{@code validate(PerformanceTestSpecification)} — Increment 1B, with the canonical model</li>
 *   <li>{@code compile(ExecutionPlan)} and {@code shard(...)} — Increment 1D</li>
 *   <li>{@code normalize(RunnerArtifactSet)} — Increment 1D</li>
 * </ul>
 *
 * @see <a href="../../../../../../../docs/adr/003-engine-spi.md">ADR 003</a>
 */
public interface ExecutionEngineProvider {

    /**
     * Stable engine identifier, e.g. {@code jmeter}. Persisted on execution plans and runner
     * shards, so it must not change once released.
     */
    String engineId();

    /** What this engine can do. Must be consistent across calls. */
    EngineCapabilities capabilities();
}
