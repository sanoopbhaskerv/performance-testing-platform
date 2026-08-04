package com.perfplatform.engine.spi;

import com.perfplatform.domain.plan.ExecutionPlan;
import com.perfplatform.domain.results.SampleSet;
import com.perfplatform.domain.spec.PerformanceTestSpecification;
import com.perfplatform.domain.target.TargetAddress;
import com.perfplatform.domain.validation.ValidationResult;

/**
 * The contract every execution engine implements.
 *
 * <p>Orchestration talks to this interface and never to an engine directly, so a new engine can be
 * added without editing orchestration, persistence or the API.
 *
 * <h2>Compile, then render</h2>
 * CLAUDE.md §14 sketches a single {@code compile(ExecutionPlan)}. This splits it in two, because
 * two genuinely different things were sharing a name:
 *
 * <ol>
 *   <li>{@link #compile} turns a specification into the <b>plan</b> — what will run. Governed:
 *       stored once, digest-verified, referenced by the run, reviewable by a person.</li>
 *   <li>{@link #render} turns that plan into the <b>file a runner executes</b>, for one shard.
 *       Derivable: regenerable from the plan at any time, different per shard, never the identity
 *       of anything.</li>
 * </ol>
 *
 * Collapsing them would mean either storing generated JMX in the row read on every reconciliation
 * pass, or recompiling at execution time — and recompiling means two runs of the same "test" can
 * quietly execute differently, which is exactly what the plan digest exists to prevent.
 *
 * <h2>What an implementation must not do</h2>
 * <ul>
 *   <li>authorize a caller or select a team;</li>
 *   <li>override global or per-team quotas;</li>
 *   <li>mutate run state in the database;</li>
 *   <li>return local filesystem paths as durable artifact references.</li>
 * </ul>
 * These are control-plane responsibilities. An engine able to do any of them could bypass the
 * controls that stop a load-generation platform behaving like a denial-of-service tool. This
 * module's dependencies are restricted to {@code platform-domain} so an engine has no access to
 * persistence, security or the API to begin with.
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

    /**
     * Checks whether this engine can execute the specification.
     *
     * <p>Engine-capability validation only — the layer that answers "can JMeter do this", not "is
     * this document well formed" or "is this test allowed". A browser workload handed to a protocol
     * engine, a load model the engine does not implement, a concurrency figure above its per-shard
     * ceiling: those belong here. Everything else already ran before this is called.
     */
    ValidationResult validate(PerformanceTestSpecification specification);

    /**
     * Compiles one of the specification's workloads into this engine's plan.
     *
     * <p>Must be deterministic: the same specification and profile always produce byte-identical
     * {@link EnginePlan#planJson()}. Its digest is the run's identity, so a compiler that emitted
     * map entries in hash order would invalidate results at random.
     *
     * <h3>One workload, not all of them</h3>
     * A run compiles a plan per workload, so a specification declaring a protocol load and a browser
     * cohort produces two plans for two engines rather than one plan that quietly executes whichever
     * workloads its engine recognised. Passing the name rather than a filtered specification keeps
     * the document whole: a workload's scenarios can reference sources, datasets and authentication
     * declared elsewhere in it.
     *
     * @param workloadName the workload to compile, which must exist and must name this engine
     * @throws IllegalArgumentException if the named workload is absent or is not this engine's
     */
    EnginePlan compile(PerformanceTestSpecification specification, String workloadName);

    /**
     * Renders the file one shard executes.
     *
     * <p>Also deterministic, for a different reason: the rendered workload is stored as an artifact
     * and is the evidence of what actually ran. Two renderings of the same inputs that differed
     * would make that evidence worthless.
     *
     * <p>The correlation header from {@code assignment} must be injected into every request the
     * workload issues. Without it, application-side observability cannot separate test traffic from
     * real traffic, which is the difference between a diagnosable result and a number.
     *
     * <p>{@code target} is resolved server-side and is the only address the workload may address.
     * An engine must never take a target from the specification: a document that named its own would
     * route around the allowlist it was validated against.
     *
     * <h3>Why the specification is passed as well as the plan</h3>
     * So that no engine has to parse its own {@code planJson} back into a model. The two cannot
     * disagree: the plan was compiled from this exact specification version, versions are immutable,
     * and the plan's digest is checked against the run before this is ever called. The specification
     * supplies the content; the plan supplies shard count and identity.
     */
    RenderedWorkload render(PerformanceTestSpecification specification, ExecutionPlan plan,
            ShardAssignment assignment, TargetAddress target);

    /**
     * Parses runner output into engine-neutral samples.
     *
     * <p>The engine owns format knowledge — which column is the latency, what marks a failure — and
     * nothing beyond this point knows the format existed.
     *
     * <h3>Parse, not summarize</h3>
     * CLAUDE.md §14 sketches {@code normalize(...)} returning a finished result. This returns
     * samples instead, because summarizing here would mean every engine computes its own
     * percentiles: two engines could then report different p95s for identical latencies, and no two
     * runs would be comparable across engines. {@code platform-results} summarizes, once, for
     * everyone — which is what {@code PercentileCalculator} already documents as the intent.
     *
     * <p>Must be pure: the same output always parses to the same samples, so a verdict can be
     * recomputed from stored artifacts rather than taken on trust.
     *
     * <p>Rows that cannot be read must be counted, never skipped. A dropped row silently improves
     * the error rate, which is how V1 reported healthier results the more corrupt its output was.
     */
    SampleSet parse(RunnerOutput output);

    /**
     * The file name this engine expects a dataset's partition to be delivered under.
     *
     * <p>The control plane slices a dataset per shard and hands each partition to a provider as a
     * key plus a file name; the engine's rendered workload refers to the same name. Those two are
     * one contract, so the engine states it — a name decided in orchestration and a name written
     * into the plan by a renderer would eventually differ, and the run would start with the file
     * present under the wrong name and every {@code ${column}} sent as literal text.
     *
     * <p>The default is the plain {@code {name}.csv} that JMeter's {@code CSVDataSet} reads from the
     * working directory. It must stay a plain file name: a name with a separator could place a file
     * outside the runner's workspace.
     */
    default String datasetFileName(String datasetName) {
        return datasetName + ".csv";
    }
}
