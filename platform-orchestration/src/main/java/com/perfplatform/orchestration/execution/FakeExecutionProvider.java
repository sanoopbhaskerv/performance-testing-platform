package com.perfplatform.orchestration.execution;

import com.perfplatform.domain.execution.ExecutionPhase;
import com.perfplatform.domain.execution.ExecutionProvider;
import com.perfplatform.domain.execution.ProvisionRequest;
import com.perfplatform.domain.run.RunnerShard.ExecutionHandle;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An execution provider that records what it was asked to do and generates no load.
 *
 * <p>This exists so the orchestration layer can be exercised end to end — trigger, admission, claim,
 * provision, observe, cancel, terminal state — without a Kubernetes cluster and without pointing
 * traffic at anything. Every acceptance criterion in Increment 1C is about the control loop, and
 * testing a control loop against a real load generator would make the tests slow, flaky and
 * dependent on infrastructure that does not exist yet.
 *
 * <p><b>This is not a production adapter.</b> The Kubernetes provider is Increment 1E. The
 * composition root must refuse to start with this provider active under a production profile; a
 * platform that reports successful runs while generating no load is worse than one that fails
 * loudly, because the reports look real.
 *
 * <h2>Determinism</h2>
 * Progress is a function of the injected {@link Clock} and the configured durations, never of wall
 * time or a background thread. A test advances the clock and observes exactly the phase that implies,
 * so restart and timeout scenarios are reproducible rather than timing-dependent.
 */
public final class FakeExecutionProvider implements ExecutionProvider {

    public static final String PROVIDER_ID = "fake";

    private final Clock clock;
    private final Duration startupDelay;
    private final Duration executionDuration;

    /**
     * Handles this provider has issued. Bounded by the runs a single process creates, and cleared
     * by {@link #reset()} between tests.
     */
    private final Map<String, Workload> workloads = new ConcurrentHashMap<>();

    /**
     * @param startupDelay      how long a workload stays {@link ExecutionPhase#PENDING} after
     *                          provisioning, modelling image pull and scheduling
     * @param executionDuration how long it then runs before succeeding
     */
    public FakeExecutionProvider(Clock clock, Duration startupDelay, Duration executionDuration) {
        this.clock = clock;
        this.startupDelay = startupDelay;
        this.executionDuration = executionDuration;
    }

    /** A provider whose workloads start and finish immediately, for tests about control flow only. */
    public static FakeExecutionProvider immediate(Clock clock) {
        return new FakeExecutionProvider(clock, Duration.ZERO, Duration.ZERO);
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public ProvisioningResult provision(ProvisionRequest request) {
        Instant now = clock.instant();

        // Deterministic and collision-free: one shard is provisioned at most once, and the name
        // carries enough identity for a person reading a test failure to know which shard it was.
        String name = "perf-%s-%d".formatted(shortId(request.runId().value().toString()),
                request.shardIndex());
        String uid = request.shardId().value().toString();

        workloads.put(uid, new Workload(request, now, false));
        return ProvisioningResult.accepted(new ExecutionHandle(PROVIDER_ID, name, uid));
    }

    @Override
    public ExecutionStatus inspect(ExecutionHandle handle) {
        Workload workload = workloads.get(handle.uid());
        if (workload == null) {
            // Matches a real provider that has garbage-collected a finished Job: nothing is running,
            // but nothing can be said about how it ended either.
            return ExecutionStatus.of(ExecutionPhase.NOT_FOUND);
        }

        Instant now = clock.instant();
        if (workload.cancelled()) {
            return ExecutionStatus.finished(ExecutionPhase.FAILED, 143,
                    workload.startedAt(), now);
        }

        Instant runningFrom = workload.provisionedAt().plus(startupDelay);
        if (now.isBefore(runningFrom)) {
            return ExecutionStatus.of(ExecutionPhase.PENDING);
        }

        Instant finishedAt = runningFrom.plus(executionDuration);
        if (now.isBefore(finishedAt)) {
            return new ExecutionStatus(ExecutionPhase.RUNNING, Optional.empty(),
                    Optional.of(runningFrom), Optional.empty());
        }
        return ExecutionStatus.finished(ExecutionPhase.SUCCEEDED, 0, runningFrom, finishedAt);
    }

    @Override
    public CancellationResult cancel(ExecutionHandle handle) {
        Workload workload = workloads.get(handle.uid());
        if (workload == null) {
            // Cancelling something already gone succeeds. Cancellation is retried by design, and a
            // second attempt that failed would leave the run unable to reach a terminal state.
            return CancellationResult.terminated("No such workload; already terminated");
        }
        workloads.put(handle.uid(), workload.cancelledCopy());
        return CancellationResult.terminated("Workload terminated");
    }

    /** Whether this provider was asked to start the given shard. For assertions in tests. */
    public boolean hasWorkload(String uid) {
        return workloads.containsKey(uid);
    }

    /** The request a shard was provisioned with, for assertions about what the runner was told. */
    public Optional<ProvisionRequest> requestFor(String uid) {
        return Optional.ofNullable(workloads.get(uid)).map(Workload::request);
    }

    /** Forgets every workload. */
    public void reset() {
        workloads.clear();
    }

    private static String shortId(String uuid) {
        return uuid.substring(0, 8);
    }

    private record Workload(ProvisionRequest request, Instant provisionedAt, boolean cancelled) {

        Instant startedAt() {
            return provisionedAt;
        }

        Workload cancelledCopy() {
            return new Workload(request, provisionedAt, true);
        }
    }
}
