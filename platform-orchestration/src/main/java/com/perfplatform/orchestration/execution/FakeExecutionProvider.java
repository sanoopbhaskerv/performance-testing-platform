package com.perfplatform.orchestration.execution;

import com.perfplatform.domain.execution.ExecutionPhase;
import com.perfplatform.domain.execution.ExecutionProvider;
import com.perfplatform.domain.execution.ProvisionRequest;
import com.perfplatform.domain.port.ArtifactStore;
import com.perfplatform.domain.port.ArtifactStore.ArtifactDescriptor;
import com.perfplatform.domain.port.ArtifactStore.ArtifactType;
import com.perfplatform.domain.port.ResultCatalog;
import com.perfplatform.domain.run.RunnerShard.ExecutionHandle;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
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

    /** How many synthetic samples a shard reports. Enough for percentiles to be meaningful. */
    private static final int SYNTHETIC_SAMPLES = 120;

    private final Clock clock;
    private final Duration startupDelay;
    private final Duration executionDuration;

    /**
     * Where the synthetic results go. Present so the full analysis path — parse, reduce, evaluate,
     * persist — is exercised without a real engine, which is the only way to verify the control
     * plane's own behaviour end to end.
     */
    private final ArtifactStore artifacts;
    private final ResultCatalog catalog;

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
        this(clock, startupDelay, executionDuration, null, null);
    }

    /**
     * @param artifacts where synthetic results are written, or {@code null} to produce none
     * @param catalog   where they are recorded, or {@code null} to record nothing
     */
    public FakeExecutionProvider(Clock clock, Duration startupDelay, Duration executionDuration,
            ArtifactStore artifacts, ResultCatalog catalog) {
        this.clock = clock;
        this.startupDelay = startupDelay;
        this.executionDuration = executionDuration;
        this.artifacts = artifacts;
        this.catalog = catalog;
    }

    /** A provider whose workloads start and finish immediately, for tests about control flow only. */
    public static FakeExecutionProvider immediate(Clock clock) {
        return new FakeExecutionProvider(clock, Duration.ZERO, Duration.ZERO);
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    /**
     * Never. This provider invents its measurements.
     *
     * <p>Every run it executes is marked synthetic on the run row itself and reports its objectives
     * as {@code INDETERMINATE}. The marking is deliberately not something a reader has to go looking
     * for: the number in a report travels into tickets and status updates, and a caveat that lives
     * only inside {@code runner-manifest.json} does not travel with it.
     */
    @Override
    public boolean generatesRealLoad() {
        return false;
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
        writeSyntheticResults(request, now);

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

    /**
     * Writes a results file that looks like the engine's, containing invented measurements.
     *
     * <p>Written at provisioning time rather than on completion because this provider models
     * execution with a clock rather than a thread — there is no later moment at which to write it.
     *
     * <p><b>These numbers describe nothing.</b> The synthetic origin is recorded in the run's own
     * evidence — a {@code runner-manifest.json} declaring {@code "syntheticData": true} beside the
     * results — so anyone reading the artifacts later can see it, not just whoever read the startup
     * log. That is the difference between a development affordance and a fabricated report.
     */
    private void writeSyntheticResults(ProvisionRequest request, Instant now) {
        if (artifacts == null) {
            return;
        }

        String jtl = syntheticJtl(request, now);
        store(request, ArtifactType.RESULTS, "results.jtl", "text/csv", jtl);

        store(request, ArtifactType.RUNNER_MANIFEST, "runner-manifest.json", "application/json",
                """
                {"runId":"%s","shardId":"%s","shardIndex":%d,"shardCount":%d,"engine":"%s",\
                "planDigest":"%s","provider":"fake","syntheticData":true,\
                "warning":"No load was generated. These measurements are invented and describe nothing.",\
                "startedAt":"%s","exitCode":0,"status":"COMPLETED"}"""
                        .formatted(request.runId(), request.shardId(), request.shardIndex(),
                                request.shardCount(), request.engine(),
                                request.planDigest().value(), now));
    }

    private void store(ProvisionRequest request, ArtifactType type, String fileName,
            String contentType, String content) {

        ArtifactStore.ArtifactReference stored = artifacts.store(
                new ArtifactDescriptor(request.teamId(), request.runId(),
                        Optional.of(request.shardId()), type, fileName, contentType),
                content.getBytes(StandardCharsets.UTF_8));

        if (catalog != null) {
            catalog.recordArtifact(stored);
        }
    }

    /**
     * A JTL in JMeter's default CSV shape.
     *
     * <p>Seeded from the shard identifier so the same shard always produces the same numbers: an
     * analysis that gave a different verdict each time it ran would make the pipeline it is meant to
     * exercise untestable.
     */
    private static String syntheticJtl(ProvisionRequest request, Instant now) {
        StringBuilder jtl = new StringBuilder(2048);
        jtl.append("timeStamp,elapsed,label,responseCode,responseMessage,threadName,dataType,")
                .append("success,failureMessage,bytes,sentBytes,grpThreads,allThreads,URL,")
                .append("Latency,IdleTime,Connect\n");

        Random deterministic = new Random(request.shardId().value().getMostSignificantBits());
        long start = now.toEpochMilli();

        // The labels the platform will look for. Objectives are scoped by workload and by scenario,
        // so synthetic results under invented labels would be unjudgeable — which is the correct
        // outcome for real results that do not match, but useless for exercising the pipeline.
        List<String> labels = request.operationLabels().isEmpty()
                ? List.of("synthetic/scenario")
                : request.operationLabels();

        // Every sample succeeds. Not realism — with a sample this small a single failure is 1.7%,
        // which breaches the 1% error-rate objective in the shipped examples, and a development
        // provider whose runs fail for reasons unrelated to the system under test teaches nothing.
        // The breach and indeterminate paths are covered by the analyzer's own tests.
        for (int i = 0; i < SYNTHETIC_SAMPLES; i++) {
            long elapsed = 40 + deterministic.nextInt(180);
            jtl.append(start + i * 250L).append(',')
                    .append(elapsed).append(',')
                    .append(labels.get(i % labels.size())).append(',')
                    .append("200,OK,tg1-1,text,true,,")
                    .append("512,180,1,1,http://synthetic/,").append(elapsed).append(",0,5\n");
        }
        return jtl.toString();
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
