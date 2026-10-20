package com.perfplatform.persistence.run;

import com.perfplatform.domain.admission.AdmissionDecision;
import com.perfplatform.domain.admission.AdmissionPolicy;
import com.perfplatform.domain.admission.CapacityLimits;
import com.perfplatform.domain.admission.CapacitySnapshot;
import com.perfplatform.domain.identity.ExecutionPlanId;
import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.identity.TestRunId;
import com.perfplatform.domain.port.TestRunRepository;
import com.perfplatform.domain.run.RunEvent;
import com.perfplatform.domain.run.RunEventType;
import com.perfplatform.domain.run.RunState;
import com.perfplatform.domain.run.RunTransition;
import com.perfplatform.domain.run.TestRun;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The durable run store, and the place the platform's concurrency guarantees are actually kept.
 *
 * <p>Three separate mechanisms, each closing a different race:
 *
 * <ul>
 *   <li><b>A unique constraint</b> on {@code (team_id, idempotency_key)} makes a duplicate trigger
 *       impossible rather than unlikely. A read-then-write check passes on two replicas at once, and
 *       the duplicate it would let through is not a wasted row but a second load test.</li>
 *   <li><b>An optimistic lock</b> on every run, so a reconciler advancing a run and a consumer
 *       cancelling it cannot silently overwrite each other. Losing that race loses a cancellation.</li>
 *   <li><b>A pessimistic mutex row</b> held for the length of a claim, so the capacity snapshot and
 *       the decision it informs cannot interleave across replicas.</li>
 * </ul>
 *
 * <p>Every one of these is a PostgreSQL guarantee. H2 remains available for local development, but
 * it is single-instance by definition and cannot demonstrate any of them — which is why the tests
 * that assert them run against PostgreSQL through Testcontainers (CLAUDE.md §25).
 */
@Repository
public class TestRunStore implements TestRunRepository {

    private static final Logger log = LoggerFactory.getLogger(TestRunStore.class);

    /** Runs holding a capacity slot. The single definition, shared with {@link RunState}. */
    private static final Set<RunState> CAPACITY_STATES = EnumSet.copyOf(
            EnumSet.allOf(RunState.class).stream().filter(RunState::holdsCapacity).toList());

    /**
     * The row every claim transaction locks before reading capacity.
     *
     * <p>A single named row rather than a PostgreSQL advisory lock, so the same code path works on
     * H2 for local development instead of branching on the database vendor inside the adapter.
     */
    private static final String ADMISSION_LOCK = "run-admission";

    private final TestRunJpaRepository runs;
    private final RunEventJpaRepository events;
    private final TransactionTemplate requiresNew;

    @PersistenceContext
    private EntityManager entityManager;

    public TestRunStore(TestRunJpaRepository runs, RunEventJpaRepository events,
            TransactionTemplate transactionTemplate) {
        this.runs = runs;
        this.events = events;

        // The insert in create() runs in its own transaction so that a constraint violation — the
        // expected outcome of a concurrent duplicate trigger — rolls back only the insert. Catching
        // it inside the caller's transaction would leave that transaction marked rollback-only, and
        // the follow-up read that resolves the duplicate could not run.
        this.requiresNew = new TransactionTemplate(transactionTemplate.getTransactionManager());
        this.requiresNew.setPropagationBehavior(Propagation.REQUIRES_NEW.value());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TestRun> find(TestRunId id) {
        return runs.findById(id.value()).map(TestRunMapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TestRun> findByIdempotencyKey(TeamId teamId, String idempotencyKey) {
        return runs.findByTeamIdAndIdempotencyKey(teamId.value(), idempotencyKey)
                .map(TestRunMapper::toDomain);
    }

    @Override
    public TestRun create(RunTransition transition) {
        TestRun run = transition.run();
        try {
            return requiresNew.execute(status -> {
                TestRunEntity saved = runs.saveAndFlush(TestRunMapper.newEntity(run));
                events.save(RunEventMapper.toEntity(transition.event()));
                return TestRunMapper.toDomain(saved);
            });
        } catch (DataIntegrityViolationException e) {
            // A concurrent replica won the constraint race with the same key. That is the mechanism
            // working: the caller gets the run that already exists rather than a second one.
            TestRun existing = findByIdempotencyKey(run.teamId(), run.trigger().idempotencyKey())
                    .orElseThrow(() -> e);

            log.info("Concurrent duplicate trigger resolved to existing runId={} teamId={} key={}",
                    existing.id(), existing.teamId(), run.trigger().idempotencyKey());
            return existing;
        }
    }

    @Override
    @Transactional
    public TestRun apply(RunTransition transition) {
        TestRun run = transition.run();
        TestRunEntity entity = runs.findById(run.id().value())
                .orElseThrow(() -> new IllegalStateException("No such test run: " + run.id()));

        // Checked explicitly as well as by @Version, because the domain object may have been read
        // in an earlier transaction and detached — Hibernate would then see a fresh entity and
        // apply the write regardless of what changed in between.
        if (entity.getLockVersion() != run.lockVersion()) {
            throw new OptimisticLockException(run.id());
        }

        TestRunMapper.copyMutableState(run, entity);
        try {
            TestRunEntity saved = runs.saveAndFlush(entity);
            events.save(RunEventMapper.toEntity(transition.event()));
            return TestRunMapper.toDomain(saved);
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new OptimisticLockException(run.id());
        }
    }

    @Override
    @Transactional
    public TestRun attachExecutionPlan(TestRunId runId, ExecutionPlanId planId) {
        TestRunEntity entity = runs.findById(runId.value())
                .orElseThrow(() -> new IllegalStateException("No such test run: " + runId));

        if (entity.getExecutionPlanId() != null && !entity.getExecutionPlanId().equals(planId.value())) {
            throw new PlanAlreadyAttachedException(runId,
                    ExecutionPlanId.of(entity.getExecutionPlanId()), planId);
        }

        entity.setExecutionPlanId(planId.value());
        return TestRunMapper.toDomain(runs.saveAndFlush(entity));
    }

    @Override
    @Transactional
    public void appendEvent(RunEvent event) {
        events.save(RunEventMapper.toEntity(event));
    }

    @Override
    @Transactional
    public List<TestRun> claimQueuedRuns(ClaimRequest request, CapacityLimits limits,
            AdmissionPolicy policy) {

        // Everything below this line is serialized across replicas. The global ceiling is only a
        // real invariant if no other claimer can observe the same free slots in between.
        acquireAdmissionMutex(request.owner());

        CapacitySnapshot snapshot = currentCapacity();
        List<TestRun> claimed = new ArrayList<>();

        for (TestRunEntity candidate : runs.lockClaimable(
                EnumSet.of(RunState.QUEUED), request.now(), request.owner())) {

            if (claimed.size() >= request.limit()) {
                break;
            }

            TeamId teamId = TeamId.of(candidate.getTeamId());
            AdmissionDecision decision = policy.evaluate(teamId, limits, snapshot);
            if (decision.isDeferred()) {
                recordFirstDeferral(candidate, decision, request);
                continue;
            }

            // The provisional admission has to be visible to the next candidate, or a pass with
            // three free slots would admit every queued run of the same team.
            snapshot = snapshot.withAdmitted(teamId);
            claimed.add(admit(candidate, request));
        }

        return List.copyOf(claimed);
    }

    @Override
    @Transactional
    public List<TestRun> claimRuns(ClaimRequest request, Set<RunState> states) {
        if (states.isEmpty()) {
            return List.of();
        }

        List<TestRun> claimed = new ArrayList<>();
        for (TestRunEntity candidate : runs.lockClaimable(states, request.now(), request.owner())) {
            if (claimed.size() >= request.limit()) {
                break;
            }
            claimed.add(lease(candidate, request));
        }
        return List.copyOf(claimed);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TestRun> findByTeam(TeamId teamId, int limit) {
        return runs.findByTeamIdOrderByRequestedAtDesc(teamId.value()).stream()
                .limit(Math.max(1, limit))
                .map(TestRunMapper::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public CapacitySnapshot currentCapacity() {
        Map<TeamId, Integer> byTeam = new HashMap<>();
        int global = 0;

        for (Object[] row : runs.countByTeamForStates(CAPACITY_STATES)) {
            TeamId teamId = TeamId.of((UUID) row[0]);
            int count = ((Number) row[1]).intValue();
            byTeam.put(teamId, count);
            global += count;
        }
        return new CapacitySnapshot(global, byTeam);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RunEvent> events(TestRunId runId, long afterSequence, int limit) {
        return events.findByRunIdAndSequenceGreaterThanOrderBySequenceAsc(
                        runId.value(), afterSequence, Limit.of(limit))
                .stream()
                .map(RunEventMapper::toDomain)
                .toList();
    }

    // ── Internals ────────────────────────────────────────────────────────

    /**
     * Takes the admission mutex for the rest of the transaction.
     *
     * <p>Blocks rather than skipping. A claimer that gave up because a peer held the lock would
     * simply not admit anything that cycle, which is a worse outcome than waiting the few
     * milliseconds a claim takes.
     */
    private void acquireAdmissionMutex(String owner) {
        entityManager.createNativeQuery(
                        "SELECT name FROM orchestration_locks WHERE name = :name FOR UPDATE")
                .setParameter("name", ADMISSION_LOCK)
                .getSingleResult();
        log.debug("Admission mutex held by owner={}", owner);
    }

    /** Moves a claimed run to {@code PROVISIONING} and leases it, writing both events. */
    private TestRun admit(TestRunEntity candidate, ClaimRequest request) {
        TestRun run = TestRunMapper.toDomain(candidate);

        RunTransition provisioning = run.transitionTo(RunState.PROVISIONING,
                RunEvent.SYSTEM_ACTOR, "Admitted by capacity policy", request.now());
        RunTransition claim = provisioning.run()
                .claimedBy(request.owner(), request.leaseExpiry(), request.now());

        TestRunMapper.copyMutableState(claim.run(), candidate);
        TestRunEntity saved = runs.saveAndFlush(candidate);

        events.save(RunEventMapper.toEntity(provisioning.event()));
        events.save(RunEventMapper.toEntity(claim.event()));

        log.info("Run admitted runId={} teamId={} owner={}",
                run.id(), run.teamId(), request.owner());

        return TestRunMapper.toDomain(saved);
    }

    /** Takes or renews the lease on a run without changing its state. */
    private TestRun lease(TestRunEntity candidate, ClaimRequest request) {
        TestRun run = TestRunMapper.toDomain(candidate);

        // The previous owner's lease lapsed — it restarted, was rescheduled, or died. The domain
        // decides whether that is worth an event, so every repository implementation records the
        // same history for the same situation.
        Optional<RunEvent> takeover = run.claimTakeoverEvent(request.owner(), request.now());

        RunTransition claim = run.claimedBy(request.owner(), request.leaseExpiry(), request.now());
        TestRunMapper.copyMutableState(claim.run(), candidate);
        TestRunEntity saved = runs.saveAndFlush(candidate);

        takeover.ifPresent(event -> {
            events.save(RunEventMapper.toEntity(event));
            log.warn("Took over runId={} teamId={} from lapsed owner={} as owner={}",
                    run.id(), run.teamId(), run.claim().orElseThrow().owner(), request.owner());
        });

        events.save(RunEventMapper.toEntity(claim.event()));
        return TestRunMapper.toDomain(saved);
    }

    /**
     * Records that a run was held back, once.
     *
     * <p>Once, not once per pass. A run waiting forty minutes behind a busy platform would otherwise
     * accumulate hundreds of identical events and bury the ones that matter. The event marks that
     * the run waited; the <em>current</em> reason is always available live from the run's status,
     * which is also the only place it can be accurate as capacity shifts.
     */
    private void recordFirstDeferral(TestRunEntity candidate, AdmissionDecision decision,
            ClaimRequest request) {

        if (events.existsByRunIdAndEventType(candidate.getId(), RunEventType.ADMISSION_DEFERRED)) {
            return;
        }

        Map<String, String> detail = new HashMap<>();
        detail.put("code", decision.code());
        detail.put("reason", decision.reason());

        events.save(RunEventMapper.toEntity(RunEvent.of(TestRunId.of(candidate.getId()),
                RunEventType.ADMISSION_DEFERRED, RunEvent.SYSTEM_ACTOR, detail, request.now())));
    }
}
