package com.perfplatform.domain.execution;

import com.perfplatform.domain.run.RunnerShard.ExecutionHandle;

import java.time.Instant;
import java.util.Optional;

/**
 * Creates, observes and stops the things that actually generate load.
 *
 * <p>This is the boundary CLAUDE.md §4 draws: the control plane decides, the provider executes, and
 * material load never runs inside the Spring Boot process. A Kubernetes provider, a remote runner
 * cell and the in-memory fake used by tests all sit behind this one interface, so orchestration
 * contains no branching on where a workload runs.
 *
 * <h2>Obligations on every implementation</h2>
 * <ul>
 *   <li><b>Never retry a material workload automatically.</b> A retried performance test is
 *       duplicate load against a real system and invalid results on top of it. Providers surface
 *       failures; only an explicit policy decision replays them.</li>
 *   <li><b>Idempotent by handle.</b> {@link #inspect} and {@link #cancel} are called repeatedly by
 *       reconciliation and must tolerate it, including after the workload has gone.</li>
 *   <li><b>Never mutate run state.</b> A provider reports; the control plane decides. Providers have
 *       no access to persistence to begin with.</li>
 *   <li><b>Never return a local filesystem path as a durable artifact reference.</b> Pod-local files
 *       vanish with the pod.</li>
 * </ul>
 */
public interface ExecutionProvider {

    /** Stable provider identifier, recorded on every handle so a stored handle stays resolvable. */
    String providerId();

    /**
     * Whether workloads this provider starts actually generate traffic against the target.
     *
     * <p>Declared by the provider rather than inferred from {@link #providerId()}, because the
     * consequence of getting it wrong is a report of measurements that describe nothing. A string
     * comparison on a provider name is a check that silently stops working the moment a provider is
     * added or renamed; a method every implementation must answer is a check that cannot be skipped.
     *
     * <p>Returning {@code false} makes every run this provider executes permanently marked as
     * synthetic, and every objective on it {@code INDETERMINATE}. That is the honest outcome: a run
     * that generated no load measured nothing, and nothing measured cannot pass. Implementations must
     * not return {@code true} because a run looks better that way.
     */
    boolean generatesRealLoad();

    /**
     * Starts one shard.
     *
     * <p>Must not throw for an ordinary failure to provision — a quota rejection or an unschedulable
     * pod is an outcome, not an exception, and the run needs to record it with a reason. Exceptions
     * are for a provider that could not be reached at all.
     */
    ProvisioningResult provision(ProvisionRequest request);

    /**
     * Reports what the provider currently observes.
     *
     * <p>Returning {@link ExecutionPhase#UNKNOWN} is correct and expected when the provider cannot
     * answer. Implementations must not guess a terminal phase to avoid returning it: a wrong
     * "finished" frees a capacity slot while the load continues.
     */
    ExecutionStatus inspect(ExecutionHandle handle);

    /**
     * Asks for the workload to stop.
     *
     * <p>Returning {@code terminated = false} means the request was accepted but termination is not
     * yet observed; the caller re-inspects rather than assuming. Cancelling an already-gone workload
     * succeeds — cancellation is retried, and a second attempt must not fail.
     */
    CancellationResult cancel(ExecutionHandle handle);

    /**
     * The outcome of a provisioning attempt.
     *
     * @param handle        the provider's reference, present only when accepted
     * @param failureReason short sanitized explanation when it was not accepted; must not carry
     *                      provider internals, since it reaches the consumer
     */
    record ProvisioningResult(Optional<ExecutionHandle> handle, Optional<String> failureReason) {

        public static ProvisioningResult accepted(ExecutionHandle handle) {
            return new ProvisioningResult(Optional.of(handle), Optional.empty());
        }

        public static ProvisioningResult rejected(String sanitizedReason) {
            return new ProvisioningResult(Optional.empty(), Optional.of(sanitizedReason));
        }

        public boolean isAccepted() {
            return handle.isPresent();
        }
    }

    /**
     * What the provider observes about a handle.
     *
     * @param phase       the observed phase
     * @param exitCode    the workload's exit code, when it has finished and reported one
     * @param startedAt   when execution began, as the provider reports it
     * @param completedAt when execution ended, as the provider reports it
     */
    record ExecutionStatus(
            ExecutionPhase phase,
            Optional<Integer> exitCode,
            Optional<Instant> startedAt,
            Optional<Instant> completedAt) {

        public static ExecutionStatus of(ExecutionPhase phase) {
            return new ExecutionStatus(phase, Optional.empty(), Optional.empty(), Optional.empty());
        }

        public static ExecutionStatus finished(ExecutionPhase phase, int exitCode,
                Instant startedAt, Instant completedAt) {
            return new ExecutionStatus(phase, Optional.of(exitCode),
                    Optional.ofNullable(startedAt), Optional.ofNullable(completedAt));
        }
    }

    /**
     * The outcome of a cancellation request.
     *
     * @param terminated whether the workload is confirmed no longer executing
     * @param detail     short sanitized note, e.g. that the handle was already gone
     */
    record CancellationResult(boolean terminated, Optional<String> detail) {

        public static CancellationResult terminated(String detail) {
            return new CancellationResult(true, Optional.ofNullable(detail));
        }

        public static CancellationResult pending(String detail) {
            return new CancellationResult(false, Optional.ofNullable(detail));
        }
    }
}
