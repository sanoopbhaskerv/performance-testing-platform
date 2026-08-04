package com.perfplatform.persistence.run;

import com.perfplatform.domain.identity.ApplicationId;
import com.perfplatform.domain.identity.ExecutionPlanId;
import com.perfplatform.domain.identity.RunnerShardId;
import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.identity.TestDefinitionId;
import com.perfplatform.domain.identity.TestDefinitionVersionId;
import com.perfplatform.domain.identity.TestRunId;
import com.perfplatform.domain.plan.ExecutionPlan;
import com.perfplatform.domain.port.ExecutionPlanRepository;
import com.perfplatform.domain.port.TestRunRepository;
import com.perfplatform.domain.run.RunFailureCode;
import com.perfplatform.domain.run.RunState;
import com.perfplatform.domain.run.RunnerShard;
import com.perfplatform.domain.run.ShardState;
import com.perfplatform.domain.run.TestRun;
import com.perfplatform.domain.spec.SpecificationDigest;
import com.perfplatform.persistence.definition.ApplicationEntity;
import com.perfplatform.persistence.definition.ApplicationJpaRepository;
import com.perfplatform.persistence.definition.TeamEntity;
import com.perfplatform.persistence.definition.TeamJpaRepository;
import com.perfplatform.persistence.definition.TestDefinitionEntity;
import com.perfplatform.persistence.definition.TestDefinitionRegistry;
import com.perfplatform.persistence.definition.TestDefinitionVersionEntity;
import com.perfplatform.persistence.plan.ExecutionPlanStore;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Shard and execution-plan storage against real PostgreSQL.
 *
 * <p>The guarantees here are about identity and immutability: one row per shard of a run, and a plan
 * that cannot be rewritten after results have been verified against its digest. Both are enforced by
 * the database, so both are asserted against it.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ShardAndPlanStorePostgresTest {

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
    }

    private static final Instant NOW = Instant.parse("2026-07-29T10:15:30Z");
    private static final String SPECIFICATION = """
            {"apiVersion":"performance.ameriprise.com/v1alpha1","kind":"PerformanceTest",\
            "metadata":{"name":"proposal-baseline","team":"advice","application":"proposal"}}""";

    @Autowired
    private TestRunStore runs;

    @Autowired
    private RunnerShardStore shards;

    @Autowired
    private ExecutionPlanStore plans;

    @Autowired
    private TestDefinitionRegistry registry;

    @Autowired
    private TeamJpaRepository teams;

    @Autowired
    private ApplicationJpaRepository applications;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;
    private TestRun run;
    private TestDefinitionVersionId versionId;

    @BeforeEach
    void seed() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                TRUNCATE TABLE run_events, runner_shards, test_runs, execution_plans,
                    test_definition_versions, test_definitions, applications, teams
                RESTART IDENTITY CASCADE
                """);

        TeamId teamId = TeamId.generate();
        teams.saveAndFlush(new TeamEntity(teamId.value(), "team-" + shortId(), NOW));

        ApplicationId applicationId = ApplicationId.generate();
        applications.saveAndFlush(new ApplicationEntity(applicationId.value(), teamId.value(),
                "app-" + shortId(), ApplicationEntity.TargetClass.NON_PRODUCTION, NOW));

        TestDefinitionEntity definition = registry.createDefinition(TestDefinitionId.generate(),
                teamId, applicationId, "definition-" + shortId(), "alice@example.com");

        TestDefinitionVersionEntity version = registry.appendVersion(
                TestDefinitionId.of(definition.getId()), TestDefinitionVersionId.generate(),
                SPECIFICATION, SpecificationDigest.ofCanonicalForm(SPECIFICATION),
                TestDefinitionVersionEntity.ValidationStatus.VALID, "alice@example.com");
        versionId = TestDefinitionVersionId.of(version.getId());

        run = runs.create(TestRun.request(TestRunId.generate(), teamId, versionId, "performance",
                new TestRun.TriggerContext("nightly", "sha256:abc", "alice@example.com"), NOW));
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private ExecutionPlan newPlan() {
        return newPlan("api-load");
    }

    /**
     * A plan for a named workload.
     *
     * <p>The name is a parameter because a run may not hold two plans for the same workload — the
     * unique key that makes provisioning idempotent forbids it — so a test needing a second plan
     * must say which other workload it is for.
     */
    private ExecutionPlan newPlan(String workloadName) {
        String planJson = "{\"planVersion\":\"v1alpha1\",\"engine\":\"jmeter\",\"workload\":\""
                + workloadName + "\"}";
        return new ExecutionPlan(ExecutionPlanId.generate(), versionId, workloadName, "jmeter",
                planJson,
                SpecificationDigest.ofCanonicalForm(planJson),
                new ExecutionPlan.CapacityEstimate(1, 200, Duration.ofMinutes(15).toSeconds()),
                NOW);
    }

    @Nested
    @DisplayName("shards")
    class Shards {

        @Test
        @DisplayName("a shard round-trips with its handle, heartbeat and outcome")
        void roundTrips() {
            RunnerShard planned = RunnerShard.planned(RunnerShardId.generate(), run.id(),
                    "jmeter", "api-load", 0, 1);
            shards.save(planned);

            RunnerShard provisioned = shards.save(planned.provisioned(
                    new RunnerShard.ExecutionHandle("kubernetes", "perf-abc-0", "uid-1"), NOW));

            assertThat(shards.find(planned.id())).hasValueSatisfying(stored -> {
                assertThat(stored.state()).isEqualTo(ShardState.PROVISIONED);
                assertThat(stored.handle()).contains(
                        new RunnerShard.ExecutionHandle("kubernetes", "perf-abc-0", "uid-1"));
                assertThat(stored.lastHeartbeat()).contains(NOW);
            });

            shards.save(provisioned.finished(ShardState.FAILED, 137,
                    RunFailureCode.RUNNER_EXITED_NON_ZERO, NOW));

            assertThat(shards.find(planned.id())).hasValueSatisfying(stored -> {
                assertThat(stored.exitCode()).contains(137);
                assertThat(stored.failureCode()).contains(RunFailureCode.RUNNER_EXITED_NON_ZERO);
                assertThat(stored.completedAt()).contains(NOW);
            });
        }

        @Test
        @DisplayName("shards are returned in index order, so reports read consistently")
        void orderedByIndex() {
            for (int index = 2; index >= 0; index--) {
                shards.save(RunnerShard.planned(RunnerShardId.generate(), run.id(), "jmeter",
                        "api-load",
                        index, 3));
            }

            assertThat(shards.findByRun(run.id()))
                    .extracting(RunnerShard::shardIndex)
                    .containsExactly(0, 1, 2);
        }

        /**
         * Stops a retried provisioning pass from creating a second record for the same shard, which
         * would double the run's apparent load and produce two sets of artifacts for one partition.
         */
        @Test
        @DisplayName("a run cannot have two shards with the same index")
        void indexIsUniquePerRun() {
            shards.save(RunnerShard.planned(RunnerShardId.generate(), run.id(), "jmeter", "api-load", 0, 1));

            assertThatThrownBy(() -> shards.save(
                    RunnerShard.planned(RunnerShardId.generate(), run.id(), "jmeter", "api-load", 0, 1)))
                    .isInstanceOf(Exception.class);
        }

        @Test
        @DisplayName("heartbeat staleness is judged against the recorded heartbeat")
        void heartbeatStaleness() {
            RunnerShard running = shards.save(
                    RunnerShard.planned(RunnerShardId.generate(), run.id(), "jmeter", "api-load", 0, 1)
                            .provisioned(new RunnerShard.ExecutionHandle("fake", "n", "u"), NOW)
                            .running(NOW));

            assertThat(running.isHeartbeatStale(NOW.plusSeconds(60), Duration.ofMinutes(5)))
                    .isFalse();
            assertThat(running.isHeartbeatStale(NOW.plusSeconds(600), Duration.ofMinutes(5)))
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("execution plans")
    class Plans {

        /**
         * Byte-for-byte, not merely semantically equal. The digest is taken over these exact bytes
         * and is what runner results are checked against, so a stored plan whose text no longer
         * hashes to its stored digest is a plan whose integrity can never be re-verified.
         */
        @Test
        @DisplayName("the plan text round-trips unchanged, so it still hashes to its stored digest")
        void planTextRoundTripsExactly() {
            ExecutionPlan plan = plans.save(run.id(), newPlan());

            assertThat(plans.find(plan.id())).hasValueSatisfying(stored -> {
                assertThat(stored.planJson()).isEqualTo(plan.planJson());
                assertThat(SpecificationDigest.ofCanonicalForm(stored.planJson()))
                        .isEqualTo(stored.planDigest());
            });
        }

        @Test
        @DisplayName("the capacity estimate round-trips")
        void capacityRoundTrips() {
            ExecutionPlan plan = plans.save(run.id(), newPlan());

            assertThat(plans.find(plan.id())).hasValueSatisfying(stored -> {
                assertThat(stored.planDigest()).isEqualTo(plan.planDigest());
                assertThat(stored.capacity().shardCount()).isEqualTo(1);
                assertThat(stored.capacity().peakVirtualUsers()).isEqualTo(200);
                assertThat(stored.capacity().estimatedDurationSeconds()).isEqualTo(900);
            });
        }

        /**
         * JSONB is a parsed representation and reorders object keys on the way out, which would
         * break the digest fidelity asserted above. This pins the column type so a later "let's
         * make plans queryable" change has to confront that first.
         */
        @Test
        @DisplayName("the plan column is text, not JSONB, so the stored bytes are not reformatted")
        void planColumnIsText() {
            assertThat(jdbc.queryForObject("""
                    SELECT data_type FROM information_schema.columns
                    WHERE table_name = 'execution_plans' AND column_name = 'plan_json'
                    """, String.class)).isEqualTo("text");
        }

        @Test
        @DisplayName("the store refuses to overwrite a stored plan")
        void storeRefusesOverwrite() {
            ExecutionPlan plan = plans.save(run.id(), newPlan());

            assertThatThrownBy(() -> plans.save(run.id(), plan))
                    .isInstanceOf(ExecutionPlanRepository.ImmutablePlanException.class)
                    .hasMessageContaining("immutable");
        }

        /**
         * The guarantee that survives a direct SQL write. A rewritten plan would retroactively
         * invalidate every result already verified against its digest, with nothing recording it.
         */
        @Test
        @DisplayName("the database refuses a direct update of a stored plan")
        void databaseRefusesUpdate() {
            ExecutionPlan plan = plans.save(run.id(), newPlan());

            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE execution_plans SET plan_json = ?::jsonb WHERE id = ?",
                    "{\"tampered\":true}", plan.id().value()))
                    .hasMessageContaining("immutable");
        }

        @Test
        @DisplayName("the database refuses to delete a stored plan")
        void databaseRefusesDelete() {
            ExecutionPlan plan = plans.save(run.id(), newPlan());

            assertThatThrownBy(() -> jdbc.update(
                    "DELETE FROM execution_plans WHERE id = ?", plan.id().value()))
                    .hasMessageContaining("immutable");
        }
    }

    @Nested
    @DisplayName("attaching a plan to a run")
    class Attaching {

        @Test
        @DisplayName("a run can be pointed at its compiled plan")
        void attaches() {
            ExecutionPlan plan = plans.save(run.id(), newPlan());

            assertThat(runs.attachExecutionPlan(run.id(), plan.id()).executionPlanId())
                    .contains(plan.id());
        }

        @Test
        @DisplayName("attaching the same plan twice is a no-op, so a retried pass is safe")
        void reattachIsIdempotent() {
            ExecutionPlan plan = plans.save(run.id(), newPlan());
            runs.attachExecutionPlan(run.id(), plan.id());

            assertThat(runs.attachExecutionPlan(run.id(), plan.id()).executionPlanId())
                    .contains(plan.id());
        }

        @Test
        @DisplayName("a run cannot be repointed at a different plan")
        void cannotRepoint() {
            runs.attachExecutionPlan(run.id(), plans.save(run.id(), newPlan()).id());
            // A different workload, because a run cannot hold two plans for the same one.
            ExecutionPlan other = plans.save(run.id(), newPlan("browser-experience"));

            assertThatThrownBy(() -> runs.attachExecutionPlan(run.id(), other.id()))
                    .isInstanceOf(TestRunRepository.PlanAlreadyAttachedException.class)
                    .hasMessageContaining("exactly one plan");
        }

        @Test
        @DisplayName("a run cannot reference a plan that does not exist")
        void planMustExist() {
            assertThatThrownBy(() -> {
                runs.attachExecutionPlan(run.id(), ExecutionPlanId.generate());
                // The foreign key is checked on flush; force it rather than relying on the
                // transaction boundary to surface later.
                jdbc.queryForObject("SELECT COUNT(*) FROM test_runs", Integer.class);
            }).isInstanceOf(Exception.class);
        }

        @Test
        @DisplayName("the run keeps its state when a plan is attached")
        void attachingDoesNotChangeState() {
            ExecutionPlan plan = plans.save(run.id(), newPlan());

            assertThat(runs.attachExecutionPlan(run.id(), plan.id()).state())
                    .isEqualTo(RunState.VALIDATING);
        }
    }

    /**
     * A run with more than one workload.
     *
     * <p>The structural change S9 made, asserted against the database because the database is what
     * enforces it. The old key was {@code (run_id, shard_index)}, which is exactly the constraint
     * that would have refused a hybrid run's second workload — silently reducing it to one.
     */
    @Nested
    @DisplayName("a run with several workloads")
    class ManyWorkloads {

        @Test
        @DisplayName("holds one plan per workload, found by the run in a stable order")
        void holdsAPlanPerWorkload() {
            plans.save(run.id(), newPlan("api-load"));
            plans.save(run.id(), newPlan("browser-experience"));

            assertThat(plans.findByRun(run.id()))
                    .extracting(ExecutionPlan::workloadName)
                    .containsExactly("api-load", "browser-experience");
        }

        @Test
        @DisplayName("refuses a second plan for the same workload, which is what makes provisioning idempotent")
        void refusesASecondPlanForOneWorkload() {
            plans.save(run.id(), newPlan("api-load"));

            // A reconciler that recompiled after a crash must not be able to create a second plan
            // for a workload that already has one — it would then start a second set of runners.
            assertThatThrownBy(() -> plans.save(run.id(), newPlan("api-load")))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("two workloads can each have a shard 0")
        void shardIndexesAreScopedToTheirWorkload() {
            shards.save(RunnerShard.planned(RunnerShardId.generate(), run.id(), "jmeter",
                    "api-load", 0, 1));
            shards.save(RunnerShard.planned(RunnerShardId.generate(), run.id(), "playwright",
                    "browser-experience", 0, 1));

            assertThat(shards.findByRun(run.id()))
                    .extracting(RunnerShard::workloadName)
                    .containsExactlyInAnyOrder("api-load", "browser-experience");
        }

        @Test
        @DisplayName("one workload still cannot have two shard 0s")
        void oneWorkloadStillHasOneShardPerIndex() {
            shards.save(RunnerShard.planned(RunnerShardId.generate(), run.id(), "jmeter",
                    "api-load", 0, 1));

            assertThatThrownBy(() -> shards.save(RunnerShard.planned(
                    RunnerShardId.generate(), run.id(), "jmeter", "api-load", 0, 1)))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }
    }
}
