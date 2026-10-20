package com.perfplatform.orchestration.support;

import com.perfplatform.domain.admission.AdmissionDecision;
import com.perfplatform.domain.admission.AdmissionPolicy;
import com.perfplatform.domain.admission.CapacityLimits;
import com.perfplatform.domain.admission.CapacitySnapshot;
import com.perfplatform.domain.identity.ExecutionPlanId;
import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.identity.TestRunId;
import com.perfplatform.domain.port.TestRunRepository;
import com.perfplatform.domain.run.RunEvent;
import com.perfplatform.domain.run.RunState;
import com.perfplatform.domain.run.RunTransition;
import com.perfplatform.domain.run.TestRun;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * An in-memory run repository for testing orchestration logic.
 *
 * <p>Exists so the use cases and the reconciler can be tested as what they are — decision logic —
 * without a database. It reproduces the behaviours those decisions depend on: idempotency by key,
 * optimistic locking, lease expiry, and admission applied inside the claim.
 *
 * <p>What it deliberately does <b>not</b> reproduce is genuine concurrency. Single-threaded and
 * unsynchronized, it cannot demonstrate that two replicas fail to claim the same run or that the
 * global ceiling holds across processes. Those are PostgreSQL guarantees and are asserted against
 * PostgreSQL in {@code TestRunStorePostgresTest}; a green test here proves the logic, not the
 * concurrency.
 */
public final class InMemoryTestRunRepository implements TestRunRepository {

    private final Map<TestRunId, TestRun> runs = new LinkedHashMap<>();
    private final List<RunEvent> events = new ArrayList<>();
    private final AtomicLong sequence = new AtomicLong();

    /** Set to make the next {@link #apply} fail, for testing how callers handle a lost race. */
    private boolean failNextApply;

    @Override
    public Optional<TestRun> find(TestRunId id) {
        return Optional.ofNullable(runs.get(id));
    }

    @Override
    public Optional<TestRun> findByIdempotencyKey(TeamId teamId, String idempotencyKey) {
        return runs.values().stream()
                .filter(run -> run.teamId().equals(teamId))
                .filter(run -> run.trigger().idempotencyKey().equals(idempotencyKey))
                .findFirst();
    }

    @Override
    public TestRun create(RunTransition transition) {
        TestRun run = transition.run();

        Optional<TestRun> existing =
                findByIdempotencyKey(run.teamId(), run.trigger().idempotencyKey());
        if (existing.isPresent()) {
            return existing.get();
        }

        runs.put(run.id(), run);
        record(transition.event());
        return run;
    }

    @Override
    public TestRun apply(RunTransition transition) {
        TestRun run = transition.run();
        TestRun stored = runs.get(run.id());
        if (stored == null) {
            throw new IllegalStateException("No such test run: " + run.id());
        }
        if (failNextApply) {
            failNextApply = false;
            throw new OptimisticLockException(run.id());
        }
        if (stored.lockVersion() != run.lockVersion()) {
            throw new OptimisticLockException(run.id());
        }

        TestRun bumped = withLockVersion(run, run.lockVersion() + 1);
        runs.put(run.id(), bumped);
        record(transition.event());
        return bumped;
    }

    @Override
    public TestRun attachExecutionPlan(TestRunId runId, ExecutionPlanId planId) {
        TestRun stored = runs.get(runId);
        if (stored == null) {
            throw new IllegalStateException("No such test run: " + runId);
        }
        if (stored.executionPlanId().isPresent() && !stored.executionPlanId().get().equals(planId)) {
            throw new PlanAlreadyAttachedException(runId, stored.executionPlanId().get(), planId);
        }
        TestRun updated = stored.withExecutionPlan(planId);
        runs.put(runId, updated);
        return updated;
    }

    @Override
    public void appendEvent(RunEvent event) {
        record(event);
    }

    @Override
    public List<TestRun> claimQueuedRuns(ClaimRequest request, CapacityLimits limits,
            AdmissionPolicy policy) {

        CapacitySnapshot snapshot = currentCapacity();
        List<TestRun> claimed = new ArrayList<>();

        for (TestRun candidate : claimable(Set.of(RunState.QUEUED), request)) {
            if (claimed.size() >= request.limit()) {
                break;
            }

            AdmissionDecision decision = policy.evaluate(candidate.teamId(), limits, snapshot);
            if (decision.isDeferred()) {
                continue;
            }

            snapshot = snapshot.withAdmitted(candidate.teamId());

            RunTransition provisioning = candidate.transitionTo(RunState.PROVISIONING,
                    RunEvent.SYSTEM_ACTOR, "Admitted by capacity policy", request.now());
            TestRun moved = apply(provisioning);
            claimed.add(apply(moved.claimedBy(request.owner(), request.leaseExpiry(), request.now())));
        }
        return List.copyOf(claimed);
    }

    @Override
    public List<TestRun> claimRuns(ClaimRequest request, Set<RunState> states) {
        List<TestRun> claimed = new ArrayList<>();
        for (TestRun candidate : claimable(states, request)) {
            if (claimed.size() >= request.limit()) {
                break;
            }
            // Part of the port's contract, not an implementation detail: the domain decides whether
            // a takeover is worth recording, and both implementations ask it the same question.
            candidate.claimTakeoverEvent(request.owner(), request.now()).ifPresent(this::record);

            claimed.add(apply(
                    candidate.claimedBy(request.owner(), request.leaseExpiry(), request.now())));
        }
        return List.copyOf(claimed);
    }

    @Override
    public List<TestRun> findByTeam(TeamId teamId, int limit) {
        return runs.values().stream()
                .filter(run -> run.teamId().equals(teamId))
                .sorted(Comparator.comparing((TestRun run) -> run.timeline().requestedAt()).reversed())
                .limit(Math.max(1, limit))
                .toList();
    }

    @Override
    public CapacitySnapshot currentCapacity() {
        Map<TeamId, Integer> byTeam = new HashMap<>();
        int global = 0;

        for (TestRun run : runs.values()) {
            if (run.holdsCapacity()) {
                byTeam.merge(run.teamId(), 1, Integer::sum);
                global++;
            }
        }
        return new CapacitySnapshot(global, byTeam);
    }

    @Override
    public List<RunEvent> events(TestRunId runId, long afterSequence, int limit) {
        return events.stream()
                .filter(event -> event.runId().equals(runId))
                .filter(event -> event.sequence() > afterSequence)
                .sorted(Comparator.comparingLong(RunEvent::sequence))
                .limit(limit)
                .toList();
    }

    // ── Test affordances ─────────────────────────────────────────────────

    /** Every event, across all runs, in the order they were appended. */
    public List<RunEvent> allEvents() {
        return List.copyOf(events);
    }

    /** All stored runs, in creation order. */
    public List<TestRun> allRuns() {
        return List.copyOf(runs.values());
    }

    /** Stores a run directly, bypassing transitions, to set up a starting state. */
    public void seed(TestRun run) {
        runs.put(run.id(), run);
    }

    /** Makes the next {@link #apply} raise, simulating a lost concurrent race. */
    public void failNextApply() {
        this.failNextApply = true;
    }

    private List<TestRun> claimable(Set<RunState> states, ClaimRequest request) {
        return runs.values().stream()
                .filter(run -> states.contains(run.state()))
                .filter(run -> run.isClaimable(request.now())
                        || run.claim().map(claim -> claim.owner().equals(request.owner()))
                                .orElse(false))
                .sorted(Comparator.comparing(run -> run.timeline().queuedAt()
                        .orElse(run.timeline().requestedAt())))
                .toList();
    }

    private void record(RunEvent event) {
        events.add(event.withSequence(sequence.incrementAndGet()));
    }

    /**
     * Rebuilds a run with a new lock version.
     *
     * <p>The aggregate has no setter for it on purpose — nothing in the domain should be able to
     * change its own optimistic-lock version, since that is the store's business.
     */
    private static TestRun withLockVersion(TestRun run, long lockVersion) {
        return new TestRun(run.id(), run.teamId(), run.definitionVersionId(), run.executionPlanId(),
                run.state(), run.environment(), run.trigger(), run.timeline(), run.cancellation(),
                run.failure(), run.claim(), run.synthetic(), lockVersion);
    }
}
