package com.perfplatform.domain.run;

import com.perfplatform.domain.identity.ExecutionPlanId;
import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.identity.TestDefinitionVersionId;
import com.perfplatform.domain.identity.TestRunId;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A single request to generate load, and everything durable about it.
 *
 * <p>Immutable. Every change produces a new instance paired with the event that explains it
 * ({@link RunTransition}), which is what keeps the audit trail complete without relying on callers
 * to remember to write one. The store persists both together.
 *
 * <p>A run always references an <em>immutable</em> definition version, never a definition. Pointing
 * at a definition would mean a run's meaning changed whenever someone edited the test, and results
 * from six months ago would describe a workload that no longer exists.
 *
 * <h2>What is not here</h2>
 * No specification, no compiled plan, no results. Those are separately stored and separately sized;
 * loading a run to answer "is it still running" must not drag a megabyte of JMX through the heap.
 * The run holds identifiers to them.
 *
 * @param id                  externally visible run identifier, also the correlation value injected
 *                            into workload traffic
 * @param teamId              owning team, resolved from the definition rather than from the request,
 *                            so a caller cannot attribute a run to a team it does not belong to
 * @param definitionVersionId the immutable version this run executes
 * @param executionPlanId     the compiled plan, absent until the run is claimed and compiled
 * @param state               current durable state
 * @param environment         environment name from the specification, denormalized for querying
 * @param trigger             how the run was requested
 * @param timeline            when each phase began
 * @param cancellation        the cancellation request, if one was made; present before the run
 *                            actually reaches {@link RunState#CANCELLED}
 * @param failure             desired failure outcome, persisted before stopping active runners;
 *                            an active run keeps its capacity until termination is observed
 * @param claim               the reconciler lease, if the run is currently owned
 * @param synthetic           whether the provider that executed this run generates no load, making
 *                            every measurement it produced invented. Carried on the run rather than
 *                            only in an artifact because this is the caveat on every number the run
 *                            reports, and a caveat someone has to go looking for does not travel
 *                            with the number it qualifies
 * @param lockVersion         optimistic-lock version; a stale write is rejected rather than applied
 */
public record TestRun(
        TestRunId id,
        TeamId teamId,
        TestDefinitionVersionId definitionVersionId,
        Optional<ExecutionPlanId> executionPlanId,
        RunState state,
        String environment,
        TriggerContext trigger,
        RunTimeline timeline,
        Optional<Cancellation> cancellation,
        Optional<Failure> failure,
        Optional<Claim> claim,
        boolean synthetic,
        long lockVersion) {

    /**
     * How a run was asked for.
     *
     * @param idempotencyKey caller-supplied key, unique per team; a retry with the same key returns
     *                       the original run rather than generating load twice
     * @param requestDigest  digest of the request that first used this key, so a key reused with
     *                       different content is rejected instead of silently returning the wrong run
     * @param requestedBy    subject identity of the caller
     */
    public record TriggerContext(String idempotencyKey, String requestDigest, String requestedBy) {
        public TriggerContext {
            requireText(idempotencyKey, "idempotencyKey");
            requireText(requestDigest, "requestDigest");
            requireText(requestedBy, "requestedBy");
        }
    }

    /**
     * Phase timestamps, all UTC.
     *
     * <p>Separate from the event log because these four are queried constantly — queue age, run
     * duration, time-to-start — and deriving them by scanning events on every query would be both
     * slow and fragile.
     */
    public record RunTimeline(
            Instant requestedAt,
            Optional<Instant> queuedAt,
            Optional<Instant> startedAt,
            Optional<Instant> completedAt) {

        public RunTimeline {
            Objects.requireNonNull(requestedAt, "requestedAt");
            queuedAt = orEmpty(queuedAt);
            startedAt = orEmpty(startedAt);
            completedAt = orEmpty(completedAt);
        }

        public static RunTimeline startingAt(Instant requestedAt) {
            return new RunTimeline(requestedAt, Optional.empty(), Optional.empty(), Optional.empty());
        }

        /**
         * Stamps the timestamp the target state implies, leaving the others alone.
         *
         * <p>Each is set once. A run re-entering a state would otherwise overwrite the original
         * time and quietly shorten every duration computed from it — and since these values feed
         * queue-age and run-duration reporting, the corruption would look like an improvement.
         */
        RunTimeline advancedTo(RunState to, Instant at) {
            return switch (to) {
                case QUEUED -> new RunTimeline(requestedAt, queuedAt.or(() -> Optional.of(at)),
                        startedAt, completedAt);
                case RUNNING -> new RunTimeline(requestedAt, queuedAt,
                        startedAt.or(() -> Optional.of(at)), completedAt);
                default -> to.isTerminal()
                        ? new RunTimeline(requestedAt, queuedAt, startedAt,
                                completedAt.or(() -> Optional.of(at)))
                        : this;
            };
        }
    }

    /**
     * A cancellation request.
     *
     * <p>Recorded when it is <em>asked for</em>, not when it takes effect. A run with a runner still
     * executing cannot stop instantly, and losing the request in the interval would mean a second
     * cancel had to be issued — or worse, that a control-plane restart resumed a run someone had
     * already stopped.
     */
    public record Cancellation(String requestedBy, Instant requestedAt, String reason) {
        public Cancellation {
            requireText(requestedBy, "requestedBy");
            Objects.requireNonNull(requestedAt, "requestedAt");
            reason = reason == null || reason.isBlank() ? "No reason given" : reason.trim();
        }
    }

    /**
     * Why a run failed.
     *
     * @param code            stable, safe to show and to alert on
     * @param sanitizedReason short human-readable text; must already be free of runner internals,
     *                        stack traces and secret values, because it is returned to consumers
     */
    public record Failure(RunFailureCode code, String sanitizedReason) {
        public Failure {
            Objects.requireNonNull(code, "code");
            sanitizedReason = sanitizedReason == null || sanitizedReason.isBlank()
                    ? code.name() : sanitizedReason.trim();
        }
    }

    /**
     * A reconciler's lease on a run.
     *
     * <p>The lease expires. If it did not, a reconciler that dies mid-provision would leave the run
     * owned forever and nothing would ever finish it. Any reconciler may take over a run whose lease
     * has lapsed, which is what makes recovery after a control-plane restart automatic rather than
     * an operator task.
     *
     * @param owner     identifies the reconciler instance holding the lease
     * @param expiresAt when the lease lapses if not renewed
     */
    public record Claim(String owner, Instant expiresAt) {
        public Claim {
            requireText(owner, "owner");
            Objects.requireNonNull(expiresAt, "expiresAt");
        }

        public boolean isExpiredAt(Instant now) {
            return !expiresAt.isAfter(now);
        }
    }

    public TestRun {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(definitionVersionId, "definitionVersionId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(trigger, "trigger");
        Objects.requireNonNull(timeline, "timeline");
        requireText(environment, "environment");
        executionPlanId = orEmpty(executionPlanId);
        cancellation = orEmpty(cancellation);
        failure = orEmpty(failure);
        claim = orEmpty(claim);
    }

    /**
     * Creates a run in {@link RunState#VALIDATING} with its first event.
     *
     * <p>Runs start validating rather than queued because being in the queue is a claim on capacity,
     * and nothing should hold capacity before the platform has decided the request is acceptable.
     */
    public static RunTransition request(TestRunId id, TeamId teamId,
            TestDefinitionVersionId definitionVersionId, String environment,
            TriggerContext trigger, Instant requestedAt) {

        TestRun run = new TestRun(id, teamId, definitionVersionId, Optional.empty(),
                RunState.VALIDATING, environment, trigger,
                RunTimeline.startingAt(requestedAt),
                Optional.empty(), Optional.empty(), Optional.empty(), false, 0L);

        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("definitionVersionId", definitionVersionId.toString());
        detail.put("environment", environment);

        return new RunTransition(run,
                RunEvent.of(id, RunEventType.REQUESTED, trigger.requestedBy(), detail, requestedAt));
    }

    /**
     * Moves to a new state, recording the change.
     *
     * @param to     the target state
     * @param actor  who caused it; {@link RunEvent#SYSTEM_ACTOR} for reconciliation
     * @param reason short, safe explanation, or {@code null}
     * @param now    the transition instant
     * @throws InvalidRunTransitionException if the matrix does not permit the transition
     */
    public RunTransition transitionTo(RunState to, String actor, String reason, Instant now) {
        RunStateMachine.requireTransition(id, state, to);

        TestRun moved = new TestRun(id, teamId, definitionVersionId, executionPlanId, to,
                environment, trigger, timeline.advancedTo(to, now), cancellation, failure,
                // A terminal run releases its lease: there is nothing left to reconcile, and a
                // lingering claim would keep it out of nothing while confusing capacity reporting.
                to.isTerminal() ? Optional.empty() : claim,
                synthetic, lockVersion);

        Map<String, String> detail = new LinkedHashMap<>();
        if (reason != null && !reason.isBlank()) {
            detail.put("reason", reason.trim());
        }
        return new RunTransition(moved, RunEvent.stateChanged(id, state, to, actor, detail, now));
    }

    /**
     * Moves to {@link RunState#FAILED}, recording the code and sanitized reason.
     *
     * <p>Separate from {@link #transitionTo} so that a failure cannot be recorded without a code:
     * a run that failed for "some reason" is not actionable at 02:00.
     */
    public RunTransition failed(RunFailureCode code, String sanitizedReason, String actor, Instant now) {
        RunTransition transition = transitionTo(RunState.FAILED, actor, sanitizedReason, now);
        TestRun withFailure = transition.run().withFailure(new Failure(code, sanitizedReason));

        Map<String, String> detail = new LinkedHashMap<>(transition.event().detail());
        detail.put("failureCode", code.name());
        return new RunTransition(withFailure,
                RunEvent.stateChanged(id, state, RunState.FAILED, actor, detail, now));
    }

    /** Persist the failure before stopping runners so a restart cannot resume provisioning/load. */
    public RunTransition failureRequested(RunFailureCode code, String reason, Instant now) {
        if (!state.mayHaveRunner()) {
            throw new IllegalStateException("Failure cleanup is only requested for active execution");
        }
        return new RunTransition(withFailure(new Failure(code, reason)),
                RunEvent.of(id, RunEventType.FAILURE_REQUESTED, RunEvent.SYSTEM_ACTOR,
                        Map.of("failureCode", code.name(), "reason", new Failure(code, reason).sanitizedReason()), now));
    }

    /**
     * Records that cancellation was requested, without changing state.
     *
     * <p>Two steps rather than one, because a run with a live runner cannot stop on request alone.
     * Persisting the desire first means a control-plane restart in the interval still results in the
     * run being stopped — the reconciler sees the request and acts on it.
     */
    public RunTransition cancellationRequested(String actor, String reason, Instant now) {
        TestRun requested = new TestRun(id, teamId, definitionVersionId, executionPlanId, state,
                environment, trigger, timeline,
                Optional.of(new Cancellation(actor, now, reason)), failure, claim,
                synthetic, lockVersion);

        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("state", state.name());
        if (reason != null && !reason.isBlank()) {
            detail.put("reason", reason.trim());
        }
        return new RunTransition(requested,
                RunEvent.of(id, RunEventType.CANCELLATION_REQUESTED, actor, detail, now));
    }

    /**
     * The event recording that a lease was taken from a different owner, if it was.
     *
     * <p>Defined here rather than in the store so that every implementation of the run repository
     * produces the same history for the same situation. It is the visible trace of a control plane
     * that failed and recovered itself, and it would be easy to lose if each adapter decided
     * separately whether to record it.
     *
     * @return empty when the run was unclaimed, or when the same owner is renewing its own lease
     */
    public Optional<RunEvent> claimTakeoverEvent(String newOwner, Instant now) {
        return claim
                .filter(held -> !held.owner().equals(newOwner))
                .map(held -> RunEvent.of(id, RunEventType.CLAIM_EXPIRED, RunEvent.SYSTEM_ACTOR,
                        RunEvent.detail("previousOwner", held.owner()), now));
    }

    /** Takes or renews the reconciler lease. Does not change state. */
    public RunTransition claimedBy(String owner, Instant expiresAt, Instant now) {
        TestRun claimed = new TestRun(id, teamId, definitionVersionId, executionPlanId, state,
                environment, trigger, timeline, cancellation, failure,
                Optional.of(new Claim(owner, expiresAt)), synthetic, lockVersion);

        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("owner", owner);
        detail.put("leaseExpiresAt", expiresAt.toString());
        return new RunTransition(claimed,
                RunEvent.of(id, RunEventType.CLAIMED, RunEvent.SYSTEM_ACTOR, detail, now));
    }

    /** Attaches the compiled execution plan. Permitted once, before provisioning. */
    public TestRun withExecutionPlan(ExecutionPlanId planId) {
        if (executionPlanId.isPresent() && !executionPlanId.get().equals(planId)) {
            throw new IllegalStateException(
                    "Run " + id + " already references execution plan " + executionPlanId.get()
                            + " and cannot be repointed at " + planId
                            + ". A run executes exactly one plan.");
        }
        return new TestRun(id, teamId, definitionVersionId, Optional.of(planId), state, environment,
                trigger, timeline, cancellation, failure, claim, synthetic, lockVersion);
    }

    /**
     * Records that this run's load was generated by a provider that generates none.
     *
     * <p>Set at provisioning, from {@link com.perfplatform.domain.execution.ExecutionProvider
     * #generatesRealLoad()}, and monotonic: there is no way back. A run whose traffic was invented
     * cannot later become one whose traffic was real, and offering a way to clear the flag would make
     * the most consequential caveat in the platform the easiest thing to remove.
     *
     * <p>Not a state transition, so it records no event — the same reasoning as
     * {@link #withExecutionPlan}. The run's history already names the provider that executed it.
     */
    public TestRun markedSynthetic() {
        return synthetic
                ? this
                : new TestRun(id, teamId, definitionVersionId, executionPlanId, state, environment,
                        trigger, timeline, cancellation, failure, claim, true, lockVersion);
    }

    private TestRun withFailure(Failure newFailure) {
        return new TestRun(id, teamId, definitionVersionId, executionPlanId, state, environment,
                trigger, timeline, cancellation, Optional.of(newFailure), claim,
                synthetic, lockVersion);
    }

    /** Whether someone has asked for this run to stop. */
    public boolean isCancellationRequested() {
        return cancellation.isPresent();
    }

    /** Whether the run currently occupies a concurrency slot. */
    public boolean holdsCapacity() {
        return state.holdsCapacity();
    }

    /** Whether the lease is absent or lapsed, making the run claimable. */
    public boolean isClaimable(Instant now) {
        return claim.map(held -> held.isExpiredAt(now)).orElse(true);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be null or blank");
        }
    }

    private static <T> Optional<T> orEmpty(Optional<T> value) {
        return value == null ? Optional.empty() : value;
    }
}
