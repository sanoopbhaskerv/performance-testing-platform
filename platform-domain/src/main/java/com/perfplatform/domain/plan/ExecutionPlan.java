package com.perfplatform.domain.plan;

import com.perfplatform.domain.identity.ExecutionPlanId;
import com.perfplatform.domain.identity.TestDefinitionVersionId;
import com.perfplatform.domain.spec.SpecificationDigest;

import java.time.Instant;
import java.util.Objects;

/**
 * What a specification compiles to for one engine, frozen.
 *
 * <p>A specification says what the consumer wants; a plan says exactly what will be executed —
 * which engine, how many shards, what each shard receives. The two are separate because compilation
 * is not free of judgement: shard count comes from a calibrated capacity profile, and that profile
 * changes as runner sizing changes. Recompiling at execution time would mean two runs of the same
 * "test" quietly executed differently.
 *
 * <p>The plan is immutable and content-addressed. Its digest travels with every runner and comes
 * back in the runner manifest; results whose digest does not match are rejected rather than merged,
 * because they measured something other than the plan the run references.
 *
 * <h2>Increment status</h2>
 * Increment 1C stores the plan and enforces its identity, but compiles only a minimal envelope —
 * engine, shard count, specification digest — because there is no real engine compiler yet. The
 * JMeter compiler arrives in 1D behind {@link ExecutionPlanCompiler} and changes what
 * {@link #planJson()} contains, not how plans are identified, stored or verified.
 *
 * <h2>One plan per workload</h2>
 * A specification can declare several workloads — a protocol load and a browser cohort measuring the
 * same system, or two protocol workloads with different shapes — and each compiles to its own plan
 * for its own engine. Before S9 a run had exactly one plan: a multi-engine specification was refused
 * at compile time, and before S6 its later workloads were silently dropped and the run reported
 * success having executed half the test.
 *
 * <p>{@link #workloadName} is therefore part of a plan's identity rather than a label on it. It is
 * what lets two plans of the same run be told apart, what names the shards that execute it, and what
 * scopes the samples it produces so an objective written about one workload is judged against that
 * workload's traffic.
 *
 * @param id                  plan identifier, referenced by the run
 * @param definitionVersionId the immutable specification version this plan was compiled from
 * @param workloadName        the workload this plan executes; unique within a run
 * @param engine              engine identifier, e.g. {@code jmeter}
 * @param planJson            the compiled plan; opaque to the domain, meaningful to its engine
 * @param planDigest          SHA-256 over {@code planJson}, checked against runner results
 * @param capacity            what the plan is expected to demand of the platform
 * @param createdAt           UTC creation instant
 */
public record ExecutionPlan(
        ExecutionPlanId id,
        TestDefinitionVersionId definitionVersionId,
        String workloadName,
        String engine,
        String planJson,
        SpecificationDigest planDigest,
        CapacityEstimate capacity,
        Instant createdAt) {

    /**
     * What executing this plan is expected to cost.
     *
     * <p>Admission control reads this rather than the specification, so that the limits apply to
     * what will actually run. A specification can be edited to understate its load; a plan is
     * compiled by the platform from a version that is already immutable.
     *
     * @param shardCount        how many runners the plan needs
     * @param peakVirtualUsers  highest concurrent virtual users across all stages
     * @param estimatedDuration total wall-clock time the stages describe, in seconds; used for the
     *                          runner deadline and for queue-wait estimates
     */
    public record CapacityEstimate(int shardCount, int peakVirtualUsers, long estimatedDurationSeconds) {
        public CapacityEstimate {
            if (shardCount < 1) {
                throw new IllegalArgumentException("shardCount must be at least 1");
            }
            if (peakVirtualUsers < 0) {
                throw new IllegalArgumentException("peakVirtualUsers must not be negative");
            }
            if (estimatedDurationSeconds < 0) {
                throw new IllegalArgumentException("estimatedDurationSeconds must not be negative");
            }
        }
    }

    public ExecutionPlan {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(definitionVersionId, "definitionVersionId");
        Objects.requireNonNull(planDigest, "planDigest");
        Objects.requireNonNull(capacity, "capacity");
        Objects.requireNonNull(createdAt, "createdAt");
        if (engine == null || engine.isBlank()) {
            throw new IllegalArgumentException("engine must not be null or blank");
        }
        if (planJson == null || planJson.isBlank()) {
            throw new IllegalArgumentException("planJson must not be null or blank");
        }
        if (workloadName == null || workloadName.isBlank()) {
            throw new IllegalArgumentException("workloadName must not be null or blank");
        }
    }

    /**
     * Whether a digest reported by a runner matches this plan.
     *
     * <p>Compared in constant time via {@link SpecificationDigest#matches}: the comparison decides
     * whether results are accepted, so it does not leak how much of the value matched.
     */
    public boolean matchesDigest(SpecificationDigest reported) {
        return planDigest.matches(reported);
    }
}
