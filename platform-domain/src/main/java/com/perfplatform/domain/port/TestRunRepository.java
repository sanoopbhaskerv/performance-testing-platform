package com.perfplatform.domain.port;

import com.perfplatform.domain.admission.AdmissionPolicy;
import com.perfplatform.domain.admission.CapacityLimits;
import com.perfplatform.domain.admission.CapacitySnapshot;
import com.perfplatform.domain.identity.ExecutionPlanId;
import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.identity.TestRunId;
import com.perfplatform.domain.run.RunEvent;
import com.perfplatform.domain.run.RunState;
import com.perfplatform.domain.run.RunTransition;
import com.perfplatform.domain.run.TestRun;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Durable storage for runs and their history.
 *
 * <p>Defined here in the domain, implemented by {@code platform-persistence}, consumed by
 * {@code platform-orchestration}. Placing the port in the domain is what lets persistence depend on
 * nothing but the domain (CLAUDE.md §9) while still implementing what orchestration needs.
 *
 * <h2>Transaction boundaries live behind this interface</h2>
 * Each method is one transaction. That is not an implementation detail the caller may ignore:
 * {@link #apply} must write the run and its event together or neither, and
 * {@link #claimQueuedRuns} must take its capacity snapshot inside the same transaction that claims
 * the work. Splitting either across calls reintroduces the races both exist to close.
 */
public interface TestRunRepository {

    /**
     * Parameters for a claim attempt.
     *
     * @param owner         identifies the claiming reconciler; stored so an operator can see which
     *                      instance owns a run
     * @param now           the claim instant
     * @param leaseDuration how long the claim holds before another reconciler may take over
     * @param limit         maximum runs to claim in this pass, bounding the work of one cycle
     */
    record ClaimRequest(String owner, Instant now, Duration leaseDuration, int limit) {
        public ClaimRequest {
            if (owner == null || owner.isBlank()) {
                throw new IllegalArgumentException("Claim owner must not be null or blank");
            }
            Objects.requireNonNull(now, "now");
            Objects.requireNonNull(leaseDuration, "leaseDuration");
            if (leaseDuration.isNegative() || leaseDuration.isZero()) {
                throw new IllegalArgumentException("leaseDuration must be positive");
            }
            if (limit < 1) {
                throw new IllegalArgumentException("limit must be at least 1");
            }
        }

        public Instant leaseExpiry() {
            return now.plus(leaseDuration);
        }
    }

    /** Raised when a write is rejected because the run changed since it was read. */
    class OptimisticLockException extends RuntimeException {
        public OptimisticLockException(TestRunId runId) {
            super("Run " + runId + " was modified concurrently. Re-read it and retry.");
        }
    }

    /**
     * Raised when a run is asked to reference a second execution plan.
     *
     * <p>Its own type rather than {@link IllegalStateException}, which Spring's persistence
     * exception translation rewrites into a data-access exception — the rule would then be
     * reported to a caller as an internal error rather than the deliberate refusal it is.
     */
    class PlanAlreadyAttachedException extends RuntimeException {
        public PlanAlreadyAttachedException(TestRunId runId, ExecutionPlanId existing,
                ExecutionPlanId attempted) {
            super("Run " + runId + " already references execution plan " + existing
                    + " and cannot be repointed at " + attempted + ". A run executes exactly one plan.");
        }
    }

    /** Raised when the same idempotency key is reused with a different request. */
    class IdempotencyConflictException extends RuntimeException {
        private final transient TestRunId existingRunId;

        public IdempotencyConflictException(String idempotencyKey, TestRunId existingRunId) {
            super("Idempotency key '" + idempotencyKey + "' was already used for run " + existingRunId
                    + " with different content. Use a new key, or resubmit the original request.");
            this.existingRunId = existingRunId;
        }

        public TestRunId existingRunId() {
            return existingRunId;
        }
    }

    Optional<TestRun> find(TestRunId id);

    /**
     * Finds the run a team's idempotency key already created, if any.
     *
     * <p>The lookup is scoped to the team because keys are chosen by consumers — two teams
     * independently using {@code nightly-baseline} must not collide, and one team must never be
     * able to observe another's run by guessing a key.
     */
    Optional<TestRun> findByIdempotencyKey(TeamId teamId, String idempotencyKey);

    /**
     * Persists a new run and its first event.
     *
     * <p>Returns the existing run when the idempotency key was already used with identical content,
     * so a retried trigger produces one run rather than two. The uniqueness decision is made by the
     * database constraint, not by a preceding read: two replicas can pass the same read check
     * simultaneously, and only the constraint is authoritative.
     *
     * @throws IdempotencyConflictException if the key was used with different content
     */
    TestRun create(RunTransition transition);

    /**
     * Persists a changed run together with its event.
     *
     * @throws OptimisticLockException if the run changed since it was read
     */
    TestRun apply(RunTransition transition);

    /**
     * Claims queued runs that admission control permits to start, and leases them to the caller.
     *
     * <p>The policy is evaluated here rather than by the caller because the decision and the claim
     * must share one transaction and one capacity snapshot. Runs the policy defers are left queued,
     * keeping their position; they are reconsidered next pass.
     *
     * <p>Implementations must guarantee that no two callers claim the same run, and that the global
     * limit holds across replicas — not merely within one.
     *
     * @return the claimed runs, already moved to {@code PROVISIONING} and leased to {@code owner}
     */
    List<TestRun> claimQueuedRuns(ClaimRequest request, CapacityLimits limits, AdmissionPolicy policy);

    /**
     * Claims runs in the given states so the caller may advance them.
     *
     * <p>Takes runs whose lease is absent or lapsed, which is what makes recovery automatic: a
     * reconciler that died mid-run leaves leases that expire, and the next reconciler picks the runs
     * up without an operator being involved.
     *
     * <p>Unlike {@link #claimQueuedRuns} this applies no admission policy and changes no state — the
     * runs are already past the point where capacity was decided. It only establishes ownership.
     */
    List<TestRun> claimRuns(ClaimRequest request, Set<RunState> states);

    /** A team's runs, newest first. Bounded, because a team accumulates runs indefinitely. */
    List<TestRun> findByTeam(TeamId teamId, int limit);

    /** Capacity currently in use. For reporting; admission uses the snapshot taken during a claim. */
    CapacitySnapshot currentCapacity();

    /**
     * Points a run at its compiled plan.
     *
     * <p>A deliberately narrow method rather than a general save. Every other write goes through
     * {@link #apply} and therefore carries an event; this one is the single exception, because
     * attaching a plan is not a state change and inventing an event for it would put noise in the
     * history that consumers read. Keeping it narrow means no other caller can use it to move a run
     * without recording why.
     *
     * @throws OptimisticLockException        if the run changed since it was read
     * @throws PlanAlreadyAttachedException   if the run already references a different plan
     */
    TestRun attachExecutionPlan(TestRunId runId, ExecutionPlanId planId);

    /**
     * Appends an event that accompanies no state change.
     *
     * <p>Separate from {@link #apply} because some things worth recording do not move a run — a
     * duplicate trigger, a deferred admission, an expired lease. Those are exactly the events that
     * explain a run's history, and requiring a state change to record one would lose them.
     */
    void appendEvent(RunEvent event);

    /**
     * A run's history in order.
     *
     * @param afterSequence return events after this sequence number; 0 for the whole history. Lets a
     *                      client that dropped a connection resume rather than re-read everything.
     * @param limit         maximum events to return
     */
    List<RunEvent> events(TestRunId runId, long afterSequence, int limit);
}
