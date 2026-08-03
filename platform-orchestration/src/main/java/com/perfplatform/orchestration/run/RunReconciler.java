package com.perfplatform.orchestration.run;

import com.perfplatform.domain.admission.AdmissionPolicy;
import com.perfplatform.domain.admission.CapacityLimits;
import com.perfplatform.domain.execution.ExecutionPhase;
import com.perfplatform.domain.execution.ExecutionProvider;
import com.perfplatform.domain.execution.ProvisionRequest;
import com.perfplatform.domain.execution.RuntimeCredential;
import com.perfplatform.domain.identity.ExecutionPlanId;
import com.perfplatform.domain.identity.RunnerShardId;
import com.perfplatform.domain.plan.ExecutionPlan;
import com.perfplatform.domain.plan.ExecutionPlanCompiler;
import com.perfplatform.domain.port.ArtifactStore.ArtifactReference;
import com.perfplatform.domain.port.DefinitionVersionCatalog;
import com.perfplatform.domain.port.DefinitionVersionCatalog.DefinitionVersion;
import com.perfplatform.domain.port.ExecutionPlanRepository;
import com.perfplatform.domain.port.RunAnalyzer;
import com.perfplatform.domain.port.RunnerShardRepository;
import com.perfplatform.domain.port.RuntimeCredentialStore;
import com.perfplatform.domain.port.TestRunRepository;
import com.perfplatform.domain.port.TestRunRepository.ClaimRequest;
import com.perfplatform.domain.results.ObjectiveOutcome;
import com.perfplatform.domain.run.RunEvent;
import com.perfplatform.domain.run.RunEventType;
import com.perfplatform.domain.run.RunFailureCode;
import com.perfplatform.domain.run.RunState;
import com.perfplatform.domain.run.RunnerShard;
import com.perfplatform.domain.run.ShardState;
import com.perfplatform.domain.run.TestRun;
import com.perfplatform.domain.spec.PerformanceTestSpecification;
import com.perfplatform.orchestration.OrchestrationSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Drives runs forward by comparing what is stored against what the runner provider observes.
 *
 * <p>The control loop. Nothing in this platform advances a run because an in-process callback fired;
 * every transition is the result of reading durable state and reconciling it against reality. That
 * is what makes a control-plane restart a non-event — V1 lost a run the moment its {@code @Async}
 * thread died, because the only record that anything was in flight was that thread.
 *
 * <h2>Pass order</h2>
 * <ol>
 *   <li><b>Recover</b> runs stuck mid-trigger.</li>
 *   <li><b>Observe</b> active runs — this is what finishes them and frees their slots.</li>
 *   <li><b>Admit</b> queued runs into the capacity that observing just released.</li>
 * </ol>
 * Observing before admitting means a pass that completes a run can start the next one immediately,
 * rather than leaving a free slot idle until the following cycle.
 *
 * <h2>Every operation is idempotent</h2>
 * A pass re-observes runs it has already seen, and may run twice against the same state after a
 * crash. Provisioning is the one step that is not naturally repeatable — it creates load — so the
 * shard's handle is persisted before the run is allowed to report {@code RUNNING}, and a shard that
 * already has a handle is never provisioned again.
 *
 * <h2>What this deliberately never does</h2>
 * It never retries a failed material run. A Kubernetes pod restart or an automatic retry after a
 * runner failure produces a second load test against a real system and two overlapping sets of
 * results. Failures are recorded and surfaced; replaying one is an explicit human decision.
 */
public final class RunReconciler {

    private static final Logger log = LoggerFactory.getLogger(RunReconciler.class);

    /** Runs that may have something executing behind them. */
    private static final EnumSet<RunState> ACTIVE_STATES =
            EnumSet.of(RunState.PROVISIONING, RunState.RUNNING, RunState.ANALYZING);

    private final TestRunRepository runs;
    private final RunnerShardRepository shards;
    private final ExecutionPlanRepository plans;
    private final DefinitionVersionCatalog versions;
    private final ExecutionPlanCompiler compiler;
    private final ExecutionProvider provider;
    private final AdmissionPolicy admissionPolicy;
    private final CapacityLimits limits;
    private final OrchestrationSettings settings;
    private final RuntimeCredentialStore runtimeCredentials;
    private final Clock clock;

    /** Rendering and analysis — everything that talks to an engine or the artifact store. */
    private final RunWorkloadService workloads;

    /**
     * Identifies this reconciler instance in claims and events.
     *
     * <p>Must be stable for the life of the process and distinct per replica — it is what lets a
     * lease be recognised as this instance's rather than a peer's, and what tells an operator which
     * pod is holding a run.
     */
    private final String owner;

    public RunReconciler(TestRunRepository runs,
            RunnerShardRepository shards,
            ExecutionPlanRepository plans,
            DefinitionVersionCatalog versions,
            ExecutionPlanCompiler compiler,
            ExecutionProvider provider,
            AdmissionPolicy admissionPolicy,
            CapacityLimits limits,
            OrchestrationSettings settings,
            RuntimeCredentialStore runtimeCredentials,
            RunWorkloadService workloads,
            Clock clock,
            String owner) {
        this.runs = Objects.requireNonNull(runs, "runs");
        this.shards = Objects.requireNonNull(shards, "shards");
        this.plans = Objects.requireNonNull(plans, "plans");
        this.versions = Objects.requireNonNull(versions, "versions");
        this.compiler = Objects.requireNonNull(compiler, "compiler");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.admissionPolicy = Objects.requireNonNull(admissionPolicy, "admissionPolicy");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.runtimeCredentials = Objects.requireNonNull(runtimeCredentials, "runtimeCredentials");
        this.workloads = Objects.requireNonNull(workloads, "workloads");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (owner == null || owner.isBlank()) {
            throw new IllegalArgumentException("Reconciler owner must not be null or blank");
        }
        this.owner = owner;
    }

    public String owner() {
        return owner;
    }

    /** Runs one full pass. Safe to call concurrently with other reconcilers. */
    public ReconciliationReport reconcile() {
        ReconciliationReport.Builder report = new ReconciliationReport.Builder();
        recoverInterruptedTriggers(report);
        observeActiveRuns(report);
        admitQueuedRuns(report);
        return report.build();
    }

    // ── Stage 1: recovery ────────────────────────────────────────────────

    /**
     * Finishes the decision a trigger did not get to record.
     *
     * <p>A trigger creates the run and then queues it in a second transaction. A process that dies
     * between the two leaves a run in {@code VALIDATING} that nothing would ever look at again. The
     * grace period keeps this sweep away from triggers that are merely in progress.
     */
    private void recoverInterruptedTriggers(ReconciliationReport.Builder report) {
        Instant now = clock.instant();
        List<TestRun> stuck = runs.claimRuns(claimRequest(now), EnumSet.of(RunState.VALIDATING));

        for (TestRun run : stuck) {
            Instant requestedAt = run.timeline().requestedAt();
            if (requestedAt.plus(settings.validationGrace()).isAfter(now)) {
                continue;
            }
            try {
                Optional<DefinitionVersion> version = versions.find(run.definitionVersionId());
                boolean executable = version.map(DefinitionVersion::executable).orElse(false);

                RunState decided = executable ? RunState.QUEUED : RunState.REJECTED;
                runs.apply(run.transitionTo(decided, RunEvent.SYSTEM_ACTOR,
                        executable
                                ? "Validation outcome recovered after an interrupted trigger"
                                : "Definition version is missing or not executable",
                        now));

                log.warn("Recovered interrupted trigger runId={} teamId={} decided={}",
                        run.id(), run.teamId(), decided);
                report.validated();
            } catch (RuntimeException e) {
                recordReconciliationError(run, "recover", e, report);
            }
        }
    }

    // ── Stage 2: observation ─────────────────────────────────────────────

    /** Observes every active run this reconciler can claim and moves it as far as it can go. */
    private void observeActiveRuns(ReconciliationReport.Builder report) {
        Instant now = clock.instant();
        for (TestRun run : runs.claimRuns(claimRequest(now), ACTIVE_STATES)) {
            try {
                advance(run, now, report);
            } catch (RuntimeException e) {
                recordReconciliationError(run, "observe", e, report);
            }
        }
    }

    private void advance(TestRun run, Instant now, ReconciliationReport.Builder report) {
        if (run.isCancellationRequested() && run.state() != RunState.ANALYZING) {
            terminate(run, now, report);
            return;
        }

        switch (run.state()) {
            case PROVISIONING -> provisionRunner(run, now, report);
            case RUNNING -> observeShards(run, now, report);
            case ANALYZING -> analyzeRun(run, now, report);
            default -> log.debug("Nothing to do for runId={} in state={}", run.id(), run.state());
        }
    }

    /**
     * Compiles the plan, creates the shards and asks the provider to start them.
     *
     * <p>Order matters throughout. The plan is stored and attached before any shard exists, so a
     * crash mid-provision leaves a run that can be resumed rather than recompiled — recompiling
     * could produce a different plan and the run's results would describe neither. The shard row and
     * its handle are stored before the run reports {@code RUNNING}, so a crash never leaves a runner
     * executing that nothing can find.
     */
    private void provisionRunner(TestRun run, Instant now, ReconciliationReport.Builder report) {
        Optional<DefinitionVersion> found = versions.find(run.definitionVersionId());
        if (found.isEmpty()) {
            fail(run, RunFailureCode.INTERNAL_ERROR,
                    "The definition version this run references is no longer readable", now, report);
            return;
        }
        DefinitionVersion version = found.get();
        List<RuntimeCredential> material = runtimeCredentials.find(run.id());
        if (missingRuntimeCredential(version.specification(), material).isPresent()) {
            fail(run, RunFailureCode.PROVISIONING_FAILED,
                    "A runtime credential required by the test definition was not available for "
                            + "provisioning", now, report);
            return;
        }

        ExecutionPlan plan = resolvePlan(run, version, now);
        TestRun attached = run.executionPlanId().isPresent()
                ? run
                : runs.attachExecutionPlan(run.id(), plan.id());

        // The provider about to generate this run's load may generate none. Recorded on the run here,
        // at the moment it becomes true and before any measurement exists that could be read without
        // the caveat. Taken from the provider's own declaration rather than from its identifier, so
        // adding a provider cannot quietly bypass it.
        TestRun withPlan = provider.generatesRealLoad() ? attached : attached.markedSynthetic();

        List<RunnerShard> existing = shards.findByRun(run.id());
        List<RunnerShard> planned = existing.isEmpty()
                ? createShards(withPlan, plan)
                : existing;

        for (RunnerShard shard : planned) {
            // A shard that already carries a handle was provisioned by an earlier pass that died
            // before recording the run's transition. Provisioning it again would start a second
            // load generator for the same shard.
            if (shard.handle().isPresent()) {
                continue;
            }

            // Rendered, sliced and stored before the runner is asked to start, so both what
            // executed and what it sent are recoverable whatever happens to the runner afterwards.
            RunWorkloadService.PreparedShard prepared;
            try {
                prepared = workloads.prepareShard(withPlan, plan, version, shard, material,
                        deadlineFor(plan), now);
            } catch (RunWorkloadService.TargetUnresolvableException
                    | RunWorkloadService.DatasetUnavailableException
                    | RunWorkloadService.SessionUnavailableException e) {
                // Neither is transient. A target that is not registered does not become registered
                // by retrying, and a dataset that does not have the declared columns will not grow
                // them — and a run that sat in PROVISIONING forever would hold a capacity slot while
                // telling the consumer nothing. The message is the actionable part.
                fail(withPlan, RunFailureCode.PROVISIONING_FAILED, e.getMessage(), now, report);
                return;
            }

            ExecutionProvider.ProvisioningResult result = provider.provision(
                    provisionRequest(withPlan, plan, shard, prepared,
                            RunWorkloadService.operationLabels(version.specification()),
                            material));

            if (!result.isAccepted()) {
                String reason = result.failureReason().orElse("The runner could not be created");
                shards.save(shard.finished(ShardState.FAILED, null,
                        RunFailureCode.PROVISIONING_FAILED, now));
                fail(withPlan, RunFailureCode.PROVISIONING_FAILED, reason, now, report);
                return;
            }

            shards.save(shard.provisioned(result.handle().orElseThrow(), now));

            // The provider is named in the history, so the evidence of what executed a run is part
            // of the run's own record rather than only of the log of whichever replica ran the pass.
            Map<String, String> provisioned = new LinkedHashMap<>();
            provisioned.put("shardIndex", String.valueOf(shard.shardIndex()));
            provisioned.put("provider", provider.providerId());
            runs.appendEvent(RunEvent.of(withPlan.id(), RunEventType.SHARD_PROVISIONED,
                    RunEvent.SYSTEM_ACTOR, provisioned, now));
        }

        runs.apply(withPlan.transitionTo(RunState.RUNNING, RunEvent.SYSTEM_ACTOR,
                withPlan.synthetic()
                        ? "All shards provisioned. The provider generates no load, so this run "
                                + "will measure nothing."
                        : "All shards provisioned", now));

        log.info("Run started runId={} teamId={} engine={} planDigest={} shards={} provider={} "
                        + "synthetic={}",
                withPlan.id(), withPlan.teamId(), plan.engine(), plan.planDigest(), planned.size(),
                provider.providerId(), withPlan.synthetic());
        report.admitted();
    }

    /**
     * Reduces the run's results, judges them, and reaches a terminal state.
     *
     * <p>A run whose objectives were not met reaches {@code FAILED}, not {@code COMPLETED}. The run
     * executed successfully but the system under test did not meet its contract, and reporting that
     * as a completed run would bury the only conclusion anyone asked for. The failure code says
     * which it was.
     */
    private void analyzeRun(TestRun run, Instant now, ReconciliationReport.Builder report) {
        Optional<DefinitionVersion> version = versions.find(run.definitionVersionId());
        Optional<ExecutionPlan> plan = run.executionPlanId().flatMap(plans::find);

        if (version.isEmpty() || plan.isEmpty()) {
            fail(run, RunFailureCode.ANALYSIS_FAILED,
                    "The definition version or execution plan this run references is no longer "
                            + "readable, so its results cannot be judged", now, report);
            return;
        }

        RunAnalyzer.Analysis analysis;
        try {
            analysis = workloads.analyze(
                    run, plan.get(), version.get(), shards.findByRun(run.id()));
        } catch (RunWorkloadService.PlanDigestMismatchException e) {
            // A terminal, non-retryable outcome with its own code. Results that describe a different
            // plan are refused rather than merged: the numbers would be real and the report would
            // look ordinary, which is precisely what makes accepting them dangerous.
            fail(run, RunFailureCode.PLAN_DIGEST_MISMATCH,
                    "A runner returned results for a different execution plan than this run "
                            + "references, so they were refused.", now, report);
            return;
        }

        // Checked before anything else about the result, and checked at all even though the objectives
        // are already indeterminate. A run with no objectives has nothing to be indeterminate about
        // and would otherwise reach COMPLETED — reported to a consumer as "Passed" — having measured
        // nothing. There must be no path by which a provider that generates no load produces a run
        // that reads as successful.
        if (run.synthetic()) {
            fail(run, RunFailureCode.NO_LOAD_GENERATED,
                    "The configured execution provider generates no load. This run produced "
                            + "synthetic measurements that describe nothing, so no objective could "
                            + "be judged.", now, report);
            return;
        }

        if (analysis.result().shardsReported() == 0) {
            fail(run, RunFailureCode.RESULTS_INCOMPLETE,
                    "No runner produced usable results, so nothing could be measured", now, report);
            return;
        }

        if (!analysis.isSuccessful()) {
            // Not every unmet objective is a breach: one that could not be judged at all is
            // reported as incomplete results, because the two send an operator to different places.
            boolean anyBreach = analysis.outcomes().stream()
                    .anyMatch(outcome -> outcome.verdict() == ObjectiveOutcome.Verdict.BREACH);

            fail(run,
                    anyBreach ? RunFailureCode.OBJECTIVE_BREACHED : RunFailureCode.RESULTS_INCOMPLETE,
                    anyBreach
                            ? "The run completed but did not meet its objectives"
                            : "The run completed but its objectives could not be judged",
                    now, report);
            return;
        }

        runs.apply(run.transitionTo(RunState.COMPLETED, RunEvent.SYSTEM_ACTOR,
                "All objectives met", now));
        runtimeCredentials.clear(run.id());
        log.info("Run completed runId={} teamId={} objectives={}",
                run.id(), run.teamId(), analysis.outcomes().size());
        report.completed();
    }

    private ExecutionPlan resolvePlan(TestRun run, DefinitionVersion version, Instant now) {
        return run.executionPlanId()
                .flatMap(plans::find)
                .orElseGet(() -> plans.save(
                        compiler.compile(ExecutionPlanId.generate(), version, now)));
    }

    private List<RunnerShard> createShards(TestRun run, ExecutionPlan plan) {
        int shardCount = plan.capacity().shardCount();
        return java.util.stream.IntStream.range(0, shardCount)
                .mapToObj(index -> shards.save(RunnerShard.planned(
                        RunnerShardId.generate(), run.id(), plan.engine(), index, shardCount)))
                .toList();
    }

    private ProvisionRequest provisionRequest(TestRun run, ExecutionPlan plan, RunnerShard shard,
            RunWorkloadService.PreparedShard prepared, List<String> operationLabels,
            List<RuntimeCredential> material) {
        return new ProvisionRequest(
                run.id(),
                run.teamId(),
                shard.id(),
                shard.shardIndex(),
                shard.shardCount(),
                plan.engine(),
                plan.id(),
                plan.planDigest(),
                prepared.workload().objectKey(),
                operationLabels,
                prepared.datasets(),
                prepared.secretFiles(),
                material,
                deadlineFor(plan));
    }

    /**
     * How long the runner is allowed to exist.
     *
     * <p>The plan's duration plus a grace for startup, ramp-down and artifact upload. This is a hard
     * stop enforced by the provider, not a suggestion: a workload that ignores its stages — a hung
     * connection pool, a runaway loop — must not keep generating load indefinitely because the
     * control plane happened to be unreachable.
     */
    private Duration deadlineFor(ExecutionPlan plan) {
        return Duration.ofSeconds(plan.capacity().estimatedDurationSeconds())
                .plus(settings.executionGrace());
    }

    /** Inspects each shard and decides whether the run has finished, failed or is still going. */
    private void observeShards(TestRun run, Instant now, ReconciliationReport.Builder report) {
        List<RunnerShard> current = shards.findByRun(run.id());
        if (current.isEmpty()) {
            fail(run, RunFailureCode.RUNNER_LOST,
                    "The run reports as executing but has no shards", now, report);
            return;
        }

        // Before the shards are inspected, so a pass that finds them all finished still merges the
        // final snapshot each runner wrote on its way out. A run that fails leaves that snapshot as
        // the only account of what was happening when it did.
        recordProgress(run, now);

        if (exceededDeadline(run, now)) {
            current.forEach(this::terminateShard);
            fail(run, RunFailureCode.DURATION_EXCEEDED,
                    "The run exceeded the wall-clock time its plan allows and was stopped",
                    now, report);
            return;
        }

        Optional<RunFailureCode> shardFailure = Optional.empty();
        boolean allTerminal = true;

        for (RunnerShard shard : current) {
            RunnerShard observed = observeShard(shard, now);
            if (observed.state() == ShardState.FAILED && shardFailure.isEmpty()) {
                // The run reports why its shard failed rather than a generic code. A runner that
                // vanished and one that exited non-zero lead an operator to look in completely
                // different places, so flattening them loses the only useful part of the message.
                shardFailure = Optional.of(
                        observed.failureCode().orElse(RunFailureCode.RUNNER_EXITED_NON_ZERO));
            }
            if (!observed.state().isTerminal()) {
                allTerminal = false;
            }
        }

        // Again, after inspecting. The local development provider publishes its snapshot from inside
        // inspect — it owns the results file rather than a pod — so a single collection before the
        // inspection would always show the previous pass's numbers, doubling the apparent staleness
        // for the one provider a person actually watches on a laptop. Recording is idempotent by
        // capture instant, so for a Kubernetes runner this second call is a no-op.
        recordProgress(run, now);

        if (shardFailure.isPresent()) {
            // Failing the whole run on one failed shard is deliberate. A partial result set measures
            // less load than the plan describes, and reporting its latencies as the test's outcome
            // would state a pass the test never demonstrated.
            fail(run, shardFailure.get(),
                    "At least one runner shard did not finish successfully", now, report);
            return;
        }

        if (allTerminal) {
            runs.apply(run.transitionTo(RunState.ANALYZING, RunEvent.SYSTEM_ACTOR,
                    "All shards finished", now));
            report.advanced();
        }
    }

    private RunnerShard observeShard(RunnerShard shard, Instant now) {
        if (shard.state().isTerminal() || shard.handle().isEmpty()) {
            return shard;
        }

        ExecutionProvider.ExecutionStatus status = provider.inspect(shard.handle().orElseThrow());

        return switch (status.phase()) {
            case PENDING -> shards.save(shard.heartbeat(now));
            case RUNNING -> shards.save(shard.state() == ShardState.RUNNING
                    ? shard.heartbeat(now)
                    : shard.running(now));
            case SUCCEEDED -> shards.save(shard.finished(ShardState.COMPLETED,
                    status.exitCode().orElse(0), null, now));
            case FAILED -> shards.save(shard.finished(ShardState.FAILED,
                    status.exitCode().orElse(null), RunFailureCode.RUNNER_EXITED_NON_ZERO, now));
            case NOT_FOUND -> shards.save(shard.finished(ShardState.FAILED, null,
                    RunFailureCode.RUNNER_LOST, now));
            // The provider could not answer. That says nothing about the workload, which may well
            // still be generating load — so nothing is concluded, and the heartbeat tolerance is
            // what eventually declares the shard lost.
            case UNKNOWN -> shard.isHeartbeatStale(now, settings.heartbeatTolerance())
                    ? declareLost(shard, now)
                    : shard;
        };
    }

    private RunnerShard declareLost(RunnerShard shard, Instant now) {
        runs.appendEvent(RunEvent.of(shard.runId(), RunEventType.HEARTBEAT_STALE,
                RunEvent.SYSTEM_ACTOR,
                RunEvent.detail("shardIndex", String.valueOf(shard.shardIndex())), now));
        return shards.save(shard.finished(ShardState.FAILED, null, RunFailureCode.RUNNER_LOST, now));
    }

    private boolean exceededDeadline(TestRun run, Instant now) {
        Optional<ExecutionPlan> plan = run.executionPlanId().flatMap(plans::find);
        Optional<Instant> startedAt = run.timeline().startedAt();
        if (plan.isEmpty() || startedAt.isEmpty()) {
            return false;
        }
        return startedAt.get().plus(deadlineFor(plan.get())).isBefore(now);
    }

    /**
     * Merges whatever the runners have published about themselves, and stores it.
     *
     * <p>Wrapped so nothing about live monitoring can stop a run from advancing. Reading object
     * storage, decoding a document written by an image released on its own schedule, and an extra
     * database write are three things that can fail transiently; a run that could not reach a
     * terminal state because a chart could not be drawn would be a strictly worse platform than one
     * with no chart.
     */
    private void recordProgress(TestRun run, Instant now) {
        try {
            run.executionPlanId().flatMap(plans::find)
                    .ifPresent(plan -> workloads.recordProgress(run, plan, now));
        } catch (RuntimeException e) {
            log.debug("Live progress could not be recorded runId={}", run.id(), e);
        }
    }

    /** Stops whatever is executing and moves the run to {@code CANCELLED}. */
    private void terminate(TestRun run, Instant now, ReconciliationReport.Builder report) {
        // A cancelled run's last snapshot is the reason somebody stopped it. Recorded before the
        // runners are torn down, while the objects they published are still the current ones.
        recordProgress(run, now);

        List<RunnerShard> current = shards.findByRun(run.id());
        boolean allStopped = true;

        for (RunnerShard shard : current) {
            if (shard.state().isTerminal() || shard.handle().isEmpty()) {
                continue;
            }
            ExecutionProvider.CancellationResult result = provider.cancel(shard.handle().orElseThrow());
            if (result.terminated()) {
                shards.save(shard.finished(ShardState.CANCELLED, null, null, now));
            } else {
                // Requested but not observed. The run stays where it is and the next pass asks
                // again; reporting CANCELLED before the load actually stopped would tell someone
                // the traffic had ceased while it had not.
                allStopped = false;
            }
        }

        if (!allStopped) {
            log.info("Cancellation pending runId={} teamId={} — awaiting runner termination",
                    run.id(), run.teamId());
            return;
        }

        runs.appendEvent(RunEvent.of(run.id(), RunEventType.EXECUTION_TERMINATED,
                RunEvent.SYSTEM_ACTOR, RunEvent.detail("shards", String.valueOf(current.size())), now));
        runs.apply(run.transitionTo(RunState.CANCELLED, RunEvent.SYSTEM_ACTOR,
                run.cancellation().map(TestRun.Cancellation::reason).orElse("Cancelled"), now));
        runtimeCredentials.clear(run.id());

        log.info("Run cancelled runId={} teamId={}", run.id(), run.teamId());
        report.cancelled();
    }

    private void terminateShard(RunnerShard shard) {
        shard.handle().ifPresent(provider::cancel);
    }

    // ── Stage 3: admission ───────────────────────────────────────────────

    /**
     * Admits queued runs into free capacity.
     *
     * <p>The admission decision and the claim happen in one transaction inside the store, against
     * one capacity snapshot. Deciding out here would let two replicas each observe the same free
     * slots and each fill them.
     */
    private void admitQueuedRuns(ReconciliationReport.Builder report) {
        Instant now = clock.instant();
        List<TestRun> admitted = runs.claimQueuedRuns(claimRequest(now), limits, admissionPolicy);

        for (TestRun run : admitted) {
            try {
                // Claimed runs arrive already in PROVISIONING: the store made the transition while
                // it held the capacity snapshot, so the slot is reserved before anything is started.
                if (run.isCancellationRequested()) {
                    terminate(run, now, report);
                    continue;
                }
                provisionRunner(run, now, report);
            } catch (RuntimeException e) {
                recordReconciliationError(run, "provision", e, report);
            }
        }
    }

    // ── Shared ───────────────────────────────────────────────────────────

    private ClaimRequest claimRequest(Instant now) {
        return new ClaimRequest(owner, now, settings.leaseDuration(), settings.claimBatchSize());
    }

    private void fail(TestRun run, RunFailureCode code, String sanitizedReason, Instant now,
            ReconciliationReport.Builder report) {
        runs.apply(run.failed(code, sanitizedReason, RunEvent.SYSTEM_ACTOR, now));
        runtimeCredentials.clear(run.id());
        log.warn("Run failed runId={} teamId={} failureCode={}", run.id(), run.teamId(), code);
        report.failed();
    }

    private static Optional<String> missingRuntimeCredential(PerformanceTestSpecification specification,
            List<RuntimeCredential> material) {
        if (specification.spec() == null) {
            return Optional.empty();
        }

        Map<String, PerformanceTestSpecification.AuthenticationSpec> authByName =
                specification.spec().authentication().stream()
                        .filter(auth -> auth.name() != null)
                        .collect(java.util.stream.Collectors.toMap(
                                PerformanceTestSpecification.AuthenticationSpec::name,
                                java.util.function.Function.identity(),
                                (left, right) -> left,
                                LinkedHashMap::new));

        List<String> available = material.stream().map(RuntimeCredential::name).toList();
        return specification.spec().workloads().stream()
                .map(PerformanceTestSpecification.WorkloadSpec::authRef)
                .filter(ref -> ref != null && !ref.isBlank())
                .map(authByName::get)
                .filter(Objects::nonNull)
                .filter(auth -> auth.secretRef() != null
                        && !"run://session".equalsIgnoreCase(auth.secretRef().trim())
                        && (auth.secretRef().regionMatches(true, 0, "run://", 0,
                                "run://".length())
                        || auth.secretRef().regionMatches(true, 0, "env://", 0,
                                "env://".length())))
                .map(PerformanceTestSpecification.AuthenticationSpec::name)
                .filter(name -> !available.contains(name))
                .findFirst();
    }

    /**
     * Records that a run could not be reconciled, without letting it stop the pass.
     *
     * <p>The run keeps its state and its lease lapses, so the next pass — possibly on another
     * replica — tries again. One run whose provider is misbehaving must not prevent every other
     * team's runs from being observed.
     *
     * <p>The exception goes to the log and never to the run's event detail: it can carry provider
     * internals and connection strings, and run events are read by consumers.
     */
    private void recordReconciliationError(TestRun run, String stage, RuntimeException error,
            ReconciliationReport.Builder report) {
        log.error("Reconciliation failed runId={} teamId={} state={} stage={}",
                run.id(), run.teamId(), run.state(), stage, error);
        report.error();
    }
}
