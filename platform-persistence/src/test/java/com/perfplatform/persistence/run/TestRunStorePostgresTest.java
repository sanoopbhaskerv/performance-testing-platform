package com.perfplatform.persistence.run;

import com.perfplatform.domain.admission.CapacityLimits;
import com.perfplatform.domain.admission.ConcurrencyAdmissionPolicy;
import com.perfplatform.domain.identity.ApplicationId;
import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.identity.TestDefinitionId;
import com.perfplatform.domain.identity.TestDefinitionVersionId;
import com.perfplatform.domain.identity.TestRunId;
import com.perfplatform.domain.port.TestRunRepository;
import com.perfplatform.domain.port.TestRunRepository.ClaimRequest;
import com.perfplatform.domain.run.RunEvent;
import com.perfplatform.domain.run.RunEventType;
import com.perfplatform.domain.run.RunState;
import com.perfplatform.domain.run.RunTransition;
import com.perfplatform.domain.run.TestRun;
import com.perfplatform.domain.spec.SpecificationDigest;
import com.perfplatform.persistence.definition.ApplicationEntity;
import com.perfplatform.persistence.definition.ApplicationJpaRepository;
import com.perfplatform.persistence.definition.TeamEntity;
import com.perfplatform.persistence.definition.TeamJpaRepository;
import com.perfplatform.persistence.definition.TestDefinitionEntity;
import com.perfplatform.persistence.definition.TestDefinitionRegistry;
import com.perfplatform.persistence.definition.TestDefinitionVersionEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The run store against real PostgreSQL.
 *
 * <p>Every guarantee this class exists to provide is a database guarantee, so every one of them is
 * asserted here rather than against an in-memory double: the idempotency constraint, the optimistic
 * lock, the append-only event log, plan immutability, and above all that two reconcilers cannot
 * claim the same run or jointly exceed the global ceiling. H2 cannot demonstrate any of it — it is
 * single-instance, has no plpgsql triggers, and does not reproduce PostgreSQL's locking semantics.
 *
 * <p>{@code @SpringBootTest} rather than {@code @DataJpaTest} because the concurrency tests need
 * real, separate transactions. A slice test wraps everything in one rolled-back transaction, which
 * would make every claim in this file appear to pass while proving nothing.
 *
 * <p>Skipped automatically when Docker is unavailable, so a developer without Docker still gets a
 * green build; CI must have Docker for these to provide their guarantee.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class TestRunStorePostgresTest {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16.2-alpine")
                    .withDatabaseName("perfplatform")
                    .withUsername("perfplatform")
                    .withPassword("perfplatform");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration/{vendor}");
        // Enough connections for the concurrent-claim tests to actually run in parallel rather
        // than serializing on the pool, which would hide the very race being tested.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "12");
    }

    private static final Instant NOW = Instant.parse("2026-07-29T10:15:30Z");
    private static final Duration LEASE = Duration.ofMinutes(2);

    private static final String SPECIFICATION = """
            {"apiVersion":"performance.ameriprise.com/v1alpha1","kind":"PerformanceTest",\
            "metadata":{"name":"proposal-baseline","team":"advice","application":"proposal"}}""";

    @Autowired
    private TestRunStore store;

    @Autowired
    private TestDefinitionRegistry registry;

    @Autowired
    private TeamJpaRepository teams;

    @Autowired
    private ApplicationJpaRepository applications;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;
    private TeamId teamId;
    private TestDefinitionVersionId versionId;

    @BeforeEach
    void seed() {
        jdbc = new JdbcTemplate(dataSource);
        resetOrchestrationTables();
        teamId = newTeam();
        versionId = newVersion(teamId);
    }

    /**
     * Empties the V2 tables between tests.
     *
     * <p>These tests commit deliberately — the concurrency assertions need genuinely separate
     * transactions, so the usual rollback-per-test does not apply. Without an explicit reset, runs
     * left in an active state accumulate and silently exhaust the global capacity ceiling, at which
     * point later tests observe a full platform and fail for reasons unrelated to what they assert.
     *
     * <p>{@code TRUNCATE} rather than {@code DELETE}: the append-only and immutability triggers on
     * {@code run_events}, {@code execution_plans} and {@code test_definition_versions} reject a row
     * delete outright. That is the behaviour under test elsewhere in this file, so it is worked
     * around here rather than weakened.
     */
    private void resetOrchestrationTables() {
        jdbc.execute("""
                TRUNCATE TABLE run_events, runner_shards, test_runs, execution_plans,
                    test_definition_versions, test_definitions, applications, teams
                RESTART IDENTITY CASCADE
                """);
    }

    private TeamId newTeam() {
        TeamId id = TeamId.generate();
        teams.saveAndFlush(new TeamEntity(id.value(), "team-" + shortId(), NOW));
        return id;
    }

    private TestDefinitionVersionId newVersion(TeamId owner) {
        ApplicationId applicationId = ApplicationId.generate();
        applications.saveAndFlush(new ApplicationEntity(applicationId.value(), owner.value(),
                "app-" + shortId(), ApplicationEntity.TargetClass.NON_PRODUCTION, NOW));

        TestDefinitionEntity definition = registry.createDefinition(TestDefinitionId.generate(),
                owner, applicationId, "definition-" + shortId(), "alice@example.com");

        TestDefinitionVersionEntity version = registry.appendVersion(
                TestDefinitionId.of(definition.getId()),
                TestDefinitionVersionId.generate(),
                SPECIFICATION,
                SpecificationDigest.ofCanonicalForm(SPECIFICATION),
                TestDefinitionVersionEntity.ValidationStatus.VALID,
                "alice@example.com");

        return TestDefinitionVersionId.of(version.getId());
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private TestRun createRun(String idempotencyKey) {
        return createRun(teamId, versionId, idempotencyKey, "sha256:" + idempotencyKey);
    }

    private TestRun createRun(TeamId owner, TestDefinitionVersionId version,
            String idempotencyKey, String fingerprint) {
        RunTransition requested = TestRun.request(TestRunId.generate(), owner, version,
                "performance",
                new TestRun.TriggerContext(idempotencyKey, fingerprint, "alice@example.com"),
                NOW);
        return store.create(requested);
    }

    private TestRun queue(TestRun run) {
        return store.apply(run.transitionTo(RunState.QUEUED, "alice@example.com", "Validated", NOW));
    }

    @Test
    void pendingFailureIsDurableWithoutReleasingCapacityOrCompletingTheRun() {
        TestRun queued = queue(createRun("pending-failure"));
        TestRun active = store.apply(queued.transitionTo(RunState.PROVISIONING, "system", null, NOW));
        store.apply(active.failureRequested(com.perfplatform.domain.run.RunFailureCode.PROVISIONING_FAILED,
                "Stop all remaining runners", NOW));
        TestRun recovered = store.find(active.id()).orElseThrow();
        assertThat(recovered.state()).isEqualTo(RunState.PROVISIONING);
        assertThat(recovered.failure().orElseThrow().code())
                .isEqualTo(com.perfplatform.domain.run.RunFailureCode.PROVISIONING_FAILED);
        assertThat(recovered.timeline().completedAt()).isEmpty();
        assertThat(recovered.holdsCapacity()).isTrue();
        assertThat(store.events(active.id(), 0, 100)).extracting(com.perfplatform.domain.run.RunEvent::type)
                .contains(com.perfplatform.domain.run.RunEventType.FAILURE_REQUESTED);
    }

    private ClaimRequest claimRequest(String owner, Instant now) {
        return new ClaimRequest(owner, now, LEASE, 20);
    }

    @Nested
    @DisplayName("migrations")
    class Migrations {

        @Test
        @DisplayName("the orchestration tables exist")
        void tablesExist() {
            List<String> tables = jdbc.queryForList(
                    "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                    String.class);

            assertThat(tables).contains("test_runs", "runner_shards", "run_events",
                    "execution_plans", "orchestration_locks");
        }

        /**
         * V1's table was renamed so the V2 model could take the name CLAUDE.md §12 specifies. The
         * rename must not have reshaped it — V1 keeps working against the same columns.
         */
        @Test
        @DisplayName("the V1 table was renamed, not reshaped")
        void v1TableRenamedIntact() {
            List<String> tables = jdbc.queryForList(
                    "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                    String.class);
            assertThat(tables).contains("v1_test_runs");

            List<String> columns = jdbc.queryForList(
                    "SELECT column_name FROM information_schema.columns WHERE table_name = 'v1_test_runs'",
                    String.class);

            assertThat(columns).contains("id", "team", "test_type", "status", "sla_result",
                    "started_at", "completed_at", "created_at", "p95latency_ms",
                    "error_rate_percent", "report_path", "failure_reason",
                    "heap_dump_report_path", "thread_dump_report_path");
        }

        @Test
        @DisplayName("the V2 run table is the new orchestration model, not V1's")
        void v2TableIsTheNewModel() {
            List<String> columns = jdbc.queryForList(
                    "SELECT column_name FROM information_schema.columns WHERE table_name = 'test_runs'",
                    String.class);

            assertThat(columns).contains("idempotency_key", "request_digest", "execution_plan_id",
                    "claim_owner", "claim_expires_at", "lock_version", "definition_version_id");
        }

        @Test
        @DisplayName("event detail is JSONB, so an operator can query inside it")
        void detailIsJsonb() {
            assertThat(jdbc.queryForObject("""
                    SELECT data_type FROM information_schema.columns
                    WHERE table_name = 'run_events' AND column_name = 'detail'
                    """, String.class)).isEqualTo("jsonb");
        }
    }

    @Nested
    @DisplayName("idempotency")
    class Idempotency {

        @Test
        @DisplayName("a run is retrievable by its team and key")
        void findableByKey() {
            TestRun run = createRun("nightly");

            assertThat(store.findByIdempotencyKey(teamId, "nightly"))
                    .hasValueSatisfying(found -> assertThat(found.id()).isEqualTo(run.id()));
        }

        /**
         * The guarantee. A read-then-write check passes on two replicas simultaneously; only the
         * constraint is authoritative, and what it prevents is a second load test.
         */
        @Test
        @DisplayName("a duplicate key returns the existing run rather than creating a second")
        void duplicateReturnsExisting() {
            TestRun first = createRun("nightly");
            TestRun second = createRun(teamId, versionId, "nightly", "sha256:nightly");

            assertThat(second.id()).isEqualTo(first.id());
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM test_runs WHERE team_id = ?", Integer.class,
                    teamId.value())).isEqualTo(1);
        }

        @Test
        @DisplayName("the database rejects a duplicate key outright")
        void constraintExists() {
            createRun("nightly");

            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO test_runs (id, team_id, definition_version_id, status, environment,
                        idempotency_key, request_digest, requested_by, requested_at, lock_version)
                    VALUES (?, ?, ?, 'VALIDATING', 'performance', 'nightly', 'sha256:x', 'mallory', ?, 0)
                    """, UUID.randomUUID(), teamId.value(), versionId.value(),
                    java.sql.Timestamp.from(NOW)))
                    .hasMessageContaining("uq_test_runs_team_idempotency");
        }

        @Test
        @DisplayName("two teams may independently use the same key")
        void keysAreScopedPerTeam() {
            createRun("nightly");

            TeamId other = newTeam();
            TestRun otherRun = createRun(other, newVersion(other), "nightly", "sha256:nightly");

            assertThat(otherRun.teamId()).isEqualTo(other);
        }
    }

    @Nested
    @DisplayName("optimistic locking")
    class OptimisticLocking {

        @Test
        @DisplayName("the lock version advances on every write")
        void versionAdvances() {
            TestRun run = createRun("nightly");
            assertThat(run.lockVersion()).isZero();
            assertThat(queue(run).lockVersion()).isEqualTo(1);
        }

        /**
         * A reconciler advancing a run and a consumer cancelling it race routinely. Without this the
         * later write wins silently, and the write that loses is sometimes the cancellation.
         */
        @Test
        @DisplayName("a write against a stale version is rejected, not applied")
        void staleWriteRejected() {
            TestRun run = queue(createRun("nightly"));
            TestRun staleCopy = store.find(run.id()).orElseThrow();

            store.apply(run.transitionTo(RunState.PROVISIONING, "system", null, NOW));

            assertThatThrownBy(() -> store.apply(
                    staleCopy.transitionTo(RunState.CANCELLED, "bob", null, NOW)))
                    .isInstanceOf(TestRunRepository.OptimisticLockException.class);

            assertThat(store.find(run.id()).orElseThrow().state()).isEqualTo(RunState.PROVISIONING);
        }
    }

    @Nested
    @DisplayName("the event log")
    class Events {

        @Test
        @DisplayName("every transition writes an event with both states")
        void transitionsWriteEvents() {
            TestRun run = queue(createRun("nightly"));

            assertThat(store.events(run.id(), 0, 100))
                    .extracting(RunEvent::type)
                    .containsExactly(RunEventType.REQUESTED, RunEventType.STATE_CHANGED);

            assertThat(store.events(run.id(), 0, 100).get(1).previousState())
                    .contains(RunState.VALIDATING);
        }

        @Test
        @DisplayName("sequence numbers increase and support resuming from a known point")
        void sequenceSupportsResume() {
            TestRun run = queue(createRun("nightly"));
            List<RunEvent> all = store.events(run.id(), 0, 100);

            assertThat(all.get(1).sequence()).isGreaterThan(all.get(0).sequence());
            assertThat(store.events(run.id(), all.get(0).sequence(), 100))
                    .containsExactly(all.get(1));
        }

        @Test
        @DisplayName("sanitized detail round-trips through JSONB")
        void detailRoundTrips() {
            TestRun run = createRun("nightly");
            store.appendEvent(RunEvent.of(run.id(), RunEventType.ADMISSION_DEFERRED,
                    RunEvent.SYSTEM_ACTOR,
                    Map.of("code", "capacity.team.exhausted", "reason", "1 of 1 running"), NOW));

            assertThat(store.events(run.id(), 0, 100))
                    .filteredOn(event -> event.type() == RunEventType.ADMISSION_DEFERRED)
                    .singleElement()
                    .satisfies(event -> assertThat(event.detail())
                            .containsEntry("code", "capacity.team.exhausted")
                            .containsEntry("reason", "1 of 1 running"));
        }

        /**
         * An audit trail an operator can quietly edit is not an audit trail. Only the database
         * trigger stops a direct SQL write, which is why this must run on PostgreSQL.
         */
        @Test
        @DisplayName("the database refuses to update a stored event")
        void updatesRejected() {
            TestRun run = createRun("nightly");
            long sequence = store.events(run.id(), 0, 1).get(0).sequence();

            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE run_events SET actor = 'mallory' WHERE sequence = ?", sequence))
                    .hasMessageContaining("append-only");
        }

        @Test
        @DisplayName("the database refuses to delete a stored event")
        void deletesRejected() {
            TestRun run = createRun("nightly");
            long sequence = store.events(run.id(), 0, 1).get(0).sequence();

            assertThatThrownBy(() -> jdbc.update(
                    "DELETE FROM run_events WHERE sequence = ?", sequence))
                    .hasMessageContaining("append-only");
        }
    }

    @Nested
    @DisplayName("claiming")
    class Claiming {

        @Test
        @DisplayName("claiming admits a queued run, moves it to provisioning and leases it")
        void claimAdmitsAndLeases() {
            TestRun run = queue(createRun("nightly"));

            List<TestRun> claimed = store.claimQueuedRuns(claimRequest("reconciler-1", NOW),
                    CapacityLimits.DEFAULTS, new ConcurrencyAdmissionPolicy());

            assertThat(claimed).singleElement().satisfies(admitted -> {
                assertThat(admitted.id()).isEqualTo(run.id());
                assertThat(admitted.state()).isEqualTo(RunState.PROVISIONING);
                assertThat(admitted.claim().orElseThrow().owner()).isEqualTo("reconciler-1");
                assertThat(admitted.claim().orElseThrow().expiresAt()).isEqualTo(NOW.plus(LEASE));
            });
        }

        @Test
        @DisplayName("both the admission and the lease are recorded as events")
        void claimIsRecorded() {
            TestRun run = queue(createRun("nightly"));
            store.claimQueuedRuns(claimRequest("reconciler-1", NOW), CapacityLimits.DEFAULTS,
                    new ConcurrencyAdmissionPolicy());

            assertThat(store.events(run.id(), 0, 100))
                    .extracting(RunEvent::type)
                    .containsExactly(RunEventType.REQUESTED, RunEventType.STATE_CHANGED,
                            RunEventType.STATE_CHANGED, RunEventType.CLAIMED);
        }

        @Test
        @DisplayName("a leased run is not claimable by another reconciler until the lease lapses")
        void leaseBlocksOthers() {
            queue(createRun("nightly"));
            store.claimQueuedRuns(claimRequest("reconciler-1", NOW), CapacityLimits.DEFAULTS,
                    new ConcurrencyAdmissionPolicy());

            assertThat(store.claimRuns(claimRequest("reconciler-2", NOW),
                    java.util.Set.of(RunState.PROVISIONING))).isEmpty();

            Instant afterLease = NOW.plus(LEASE).plusSeconds(1);
            assertThat(store.claimRuns(claimRequest("reconciler-2", afterLease),
                    java.util.Set.of(RunState.PROVISIONING))).hasSize(1);
        }

        @Test
        @DisplayName("taking over a lapsed lease is recorded")
        void takeoverIsRecorded() {
            TestRun run = queue(createRun("nightly"));
            store.claimQueuedRuns(claimRequest("reconciler-1", NOW), CapacityLimits.DEFAULTS,
                    new ConcurrencyAdmissionPolicy());

            store.claimRuns(claimRequest("reconciler-2", NOW.plus(LEASE).plusSeconds(1)),
                    java.util.Set.of(RunState.PROVISIONING));

            assertThat(store.events(run.id(), 0, 100))
                    .extracting(RunEvent::type)
                    .contains(RunEventType.CLAIM_EXPIRED);
        }

        @Test
        @DisplayName("a deferred run keeps its queue position and is recorded once, not per pass")
        void deferralRecordedOnce() {
            // Queued a minute apart so the pass admits the older one deterministically. With equal
            // timestamps the tiebreak is the identifier, and the test would pass or fail on which
            // random UUID happened to sort first.
            store.apply(createRun("first")
                    .transitionTo(RunState.QUEUED, "alice", null, NOW.minusSeconds(60)));
            TestRun second = queue(createRun("second"));

            ConcurrencyAdmissionPolicy policy = new ConcurrencyAdmissionPolicy();
            store.claimQueuedRuns(claimRequest("reconciler-1", NOW), CapacityLimits.DEFAULTS, policy);
            store.claimQueuedRuns(claimRequest("reconciler-1", NOW), CapacityLimits.DEFAULTS, policy);
            store.claimQueuedRuns(claimRequest("reconciler-1", NOW), CapacityLimits.DEFAULTS, policy);

            assertThat(store.find(second.id()).orElseThrow().state()).isEqualTo(RunState.QUEUED);
            assertThat(store.events(second.id(), 0, 100))
                    .filteredOn(event -> event.type() == RunEventType.ADMISSION_DEFERRED)
                    .hasSize(1);
        }

        @Test
        @DisplayName("the queue is ordered by age, so ordering is deterministic and auditable")
        void queueIsOrderedByAge() {
            TeamId teamA = newTeam();
            TeamId teamB = newTeam();

            TestRun older = store.apply(createRun(teamA, newVersion(teamA), "older", "sha256:a")
                    .transitionTo(RunState.QUEUED, "alice", null, NOW.minusSeconds(60)));
            store.apply(createRun(teamB, newVersion(teamB), "newer", "sha256:b")
                    .transitionTo(RunState.QUEUED, "alice", null, NOW));

            List<TestRun> claimed = store.claimQueuedRuns(
                    new ClaimRequest("reconciler-1", NOW, LEASE, 1),
                    CapacityLimits.DEFAULTS, new ConcurrencyAdmissionPolicy());

            assertThat(claimed).singleElement()
                    .satisfies(first -> assertThat(first.id()).isEqualTo(older.id()));
        }
    }

    @Nested
    @DisplayName("concurrency")
    class Concurrency {

        /**
         * The claim that matters most. Two reconcilers running at the same instant must not both
         * start the same run — the duplicate would be a second load generator against the same
         * target, with both sets of results invalid.
         */
        @Test
        @DisplayName("two reconcilers claiming simultaneously never claim the same run")
        void noDoubleClaim() throws Exception {
            List<TestRun> queued = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                TeamId owner = newTeam();
                queued.add(store.apply(createRun(owner, newVersion(owner), "run-" + i, "sha256:" + i)
                        .transitionTo(RunState.QUEUED, "alice", null, NOW)));
            }

            List<List<TestRun>> results = inParallel(4, index -> () ->
                    store.claimQueuedRuns(claimRequest("reconciler-" + index, NOW),
                            CapacityLimits.DEFAULTS, new ConcurrencyAdmissionPolicy()));

            List<UUID> claimedIds = results.stream()
                    .flatMap(List::stream)
                    .map(run -> run.id().value())
                    .toList();

            assertThat(claimedIds).doesNotHaveDuplicates();
            assertThat(claimedIds).hasSize(8);
            assertThat(queued).allSatisfy(run ->
                    assertThat(store.find(run.id()).orElseThrow().state())
                            .isEqualTo(RunState.PROVISIONING));
        }

        /**
         * Each decision in isolation is correct; without serializing the critical section the
         * aggregate is not. Four reconcilers each observing three free slots would admit twelve.
         */
        @Test
        @DisplayName("the global ceiling holds across concurrent reconcilers")
        void globalCeilingHoldsUnderConcurrency() throws Exception {
            CapacityLimits limits = new CapacityLimits(3, 1, Map.of());

            for (int i = 0; i < 10; i++) {
                TeamId owner = newTeam();
                store.apply(createRun(owner, newVersion(owner), "run-" + i, "sha256:" + i)
                        .transitionTo(RunState.QUEUED, "alice", null, NOW));
            }

            inParallel(4, index -> () -> store.claimQueuedRuns(
                    claimRequest("reconciler-" + index, NOW), limits,
                    new ConcurrencyAdmissionPolicy()));

            assertThat(store.currentCapacity().globalActive()).isEqualTo(3);
        }

        @Test
        @DisplayName("the per-team ceiling holds across concurrent reconcilers")
        void teamCeilingHoldsUnderConcurrency() throws Exception {
            for (int i = 0; i < 10; i++) {
                store.apply(createRun("run-" + i).transitionTo(RunState.QUEUED, "alice", null, NOW));
            }

            inParallel(4, index -> () -> store.claimQueuedRuns(
                    claimRequest("reconciler-" + index, NOW), CapacityLimits.DEFAULTS,
                    new ConcurrencyAdmissionPolicy()));

            assertThat(store.currentCapacity().activeFor(teamId)).isEqualTo(1);
        }

        @Test
        @DisplayName("concurrent duplicate triggers produce exactly one run")
        void concurrentDuplicateTriggersProduceOneRun() throws Exception {
            AtomicInteger distinctRuns = new AtomicInteger();

            List<TestRun> created = inParallel(6, index -> () ->
                    createRun(teamId, versionId, "concurrent-nightly", "sha256:concurrent"));

            created.stream().map(run -> run.id().value()).distinct()
                    .forEach(id -> distinctRuns.incrementAndGet());

            assertThat(distinctRuns.get()).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM test_runs WHERE idempotency_key = 'concurrent-nightly'",
                    Integer.class)).isEqualTo(1);
        }

        /**
         * Runs each task on its own thread, released together, so they genuinely contend rather
         * than executing one after another.
         */
        private <T> List<T> inParallel(int threads, java.util.function.IntFunction<Callable<T>> task)
                throws Exception {

            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CyclicBarrier startTogether = new CyclicBarrier(threads);
            try {
                List<Future<T>> futures = new ArrayList<>();
                for (int i = 0; i < threads; i++) {
                    Callable<T> work = task.apply(i);
                    futures.add(pool.submit(() -> {
                        startTogether.await(10, TimeUnit.SECONDS);
                        return work.call();
                    }));
                }

                List<T> results = new ArrayList<>();
                for (Future<T> future : futures) {
                    results.add(future.get(30, TimeUnit.SECONDS));
                }
                return results;
            } finally {
                pool.shutdownNow();
            }
        }
    }
}
