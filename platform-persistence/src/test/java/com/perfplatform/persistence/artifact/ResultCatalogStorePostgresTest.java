package com.perfplatform.persistence.artifact;

import com.perfplatform.domain.identity.ApplicationId;
import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.identity.TestDefinitionId;
import com.perfplatform.domain.identity.TestDefinitionVersionId;
import com.perfplatform.domain.identity.TestRunId;
import com.perfplatform.domain.port.ArtifactStore.ArtifactType;
import com.perfplatform.domain.results.NormalizedRunResult;
import com.perfplatform.domain.results.ObjectiveOutcome;
import com.perfplatform.domain.results.ObjectiveOutcome.Verdict;
import com.perfplatform.domain.results.OperationMetrics;
import com.perfplatform.domain.run.TestRun;
import com.perfplatform.domain.spec.SpecificationDigest;
import com.perfplatform.persistence.definition.ApplicationEntity;
import com.perfplatform.persistence.definition.ApplicationJpaRepository;
import com.perfplatform.persistence.definition.TeamEntity;
import com.perfplatform.persistence.definition.TeamJpaRepository;
import com.perfplatform.persistence.definition.TestDefinitionEntity;
import com.perfplatform.persistence.definition.TestDefinitionRegistry;
import com.perfplatform.persistence.definition.TestDefinitionVersionEntity;
import com.perfplatform.persistence.run.TestRunStore;
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
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A run's measurements and verdicts, against real PostgreSQL.
 *
 * <p>The guarantees here are the ones the report depends on: what analysis wrote is exactly what a
 * report reads back, and re-analysing a run replaces its numbers rather than accumulating a second
 * set. Both are enforced by the database — the round trip by column types, the replacement by a
 * unique constraint — so both are asserted against it rather than against an in-memory fake.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ResultCatalogStorePostgresTest {

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
    private static final Instant MEASURED_FROM = Instant.parse("2026-07-29T10:16:00Z");
    private static final Instant MEASURED_TO = Instant.parse("2026-07-29T10:31:00Z");

    private static final String SPECIFICATION = """
            {"apiVersion":"performance.ameriprise.com/v1alpha1","kind":"PerformanceTest",\
            "metadata":{"name":"proposal-baseline","team":"advice","application":"proposal"}}""";

    @Autowired
    private ResultCatalogStore catalog;

    @Autowired
    private TestRunStore runs;

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

    @BeforeEach
    void seed() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                TRUNCATE TABLE run_metrics, objective_results, artifacts, run_events, runner_shards,
                    test_runs, execution_plans, test_definition_versions, test_definitions,
                    applications, teams
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

        run = runs.create(TestRun.request(TestRunId.generate(), teamId,
                TestDefinitionVersionId.of(version.getId()), "performance",
                new TestRun.TriggerContext("nightly", "sha256:abc", "alice@example.com"), NOW));
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static OperationMetrics metrics(String name, long samples, long errors, long p95) {
        return new OperationMetrics(name, samples, errors, 3,
                12, 940, 180, 150, 400, p95, 900, 41_500, 250L);
    }

    private NormalizedRunResult result(int shardsReported, long overallP95) {
        Map<String, OperationMetrics> byOperation = new LinkedHashMap<>();
        byOperation.put("checkout-load/browse", metrics("checkout-load/browse", 700, 2, 320));
        byOperation.put("checkout-load/checkout", metrics("checkout-load/checkout", 300, 5, 610));

        return new NormalizedRunResult(run.id(), "jmeter",
                Optional.of(MEASURED_FROM), Optional.of(MEASURED_TO),
                2, shardsReported,
                metrics(OperationMetrics.ALL, 1000, 7, overallP95),
                byOperation);
    }

    private static List<ObjectiveOutcome> outcomes(Verdict verdict) {
        return List.of(new ObjectiveOutcome("http.response.time@checkout-load#p95",
                "http.response.time", "checkout-load", verdict, 610, 750, "p95 was 610ms."));
    }

    @Nested
    @DisplayName("measurements")
    class Measurements {

        /**
         * The whole reason to persist rather than recompute. Every value has to survive the round trip
         * exactly — these are the numbers a verdict was derived from, and a report showing a p95 that
         * differs from the one that was judged would be worse than showing none.
         */
        @Test
        @DisplayName("what analysis wrote is exactly what a report reads back")
        void roundTripsExactly() {
            NormalizedRunResult written = result(2, 590);

            catalog.recordAnalysis(run.id(), written, outcomes(Verdict.PASS));

            assertThat(catalog.runMetricsFor(run.id()))
                    .hasValueSatisfying(read -> assertThat(read).isEqualTo(written));
        }

        /** Operation order is part of the contract: a report must not reshuffle between two opens. */
        @Test
        @DisplayName("operations come back in a stable order")
        void orderIsStable() {
            catalog.recordAnalysis(run.id(), result(2, 590), outcomes(Verdict.PASS));

            assertThat(catalog.runMetricsFor(run.id()).orElseThrow().operationNames())
                    .containsExactly("checkout-load/browse", "checkout-load/checkout");
        }

        /**
         * Incompleteness travels with the numbers. A p95 reduced from one of two shards describes half
         * the planned load, and a reader must not have to consult another table to learn that.
         */
        @Test
        @DisplayName("an incomplete result stays incomplete when read back")
        void incompletenessSurvives() {
            catalog.recordAnalysis(run.id(), result(1, 590), outcomes(Verdict.INDETERMINATE));

            NormalizedRunResult read = catalog.runMetricsFor(run.id()).orElseThrow();
            assertThat(read.shardsReported()).isEqualTo(1);
            assertThat(read.shardsExpected()).isEqualTo(2);
            assertThat(read.isComplete()).isFalse();
        }

        /**
         * Absent, not zero. "Nothing has been measured" and "everything measured zero" are different
         * facts, and a report that rendered the first as the second would describe a finished test.
         */
        @Test
        @DisplayName("a run with no analysis has no measurements, rather than zeroed ones")
        void unanalysedRunHasNoMetrics() {
            assertThat(catalog.runMetricsFor(run.id())).isEmpty();
        }

        /**
         * The case the unique constraint on {@code (run_id, operation)} exists for. Appending would
         * eventually show one operation twice with different numbers, which is worse than no report.
         */
        @Test
        @DisplayName("re-analysing replaces the measurements instead of adding a second set")
        void reanalysisReplaces() {
            catalog.recordAnalysis(run.id(), result(2, 590), outcomes(Verdict.PASS));
            catalog.recordAnalysis(run.id(), result(2, 615), outcomes(Verdict.BREACH));

            Long rows = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM run_metrics WHERE run_id = ?", Long.class, run.id().value());

            // Two operations plus the aggregate. Not six.
            assertThat(rows).isEqualTo(3L);
            assertThat(catalog.runMetricsFor(run.id()).orElseThrow().overall().p95Millis())
                    .isEqualTo(615);
            assertThat(catalog.objectiveOutcomesFor(run.id()))
                    .extracting(ObjectiveOutcome::verdict)
                    .containsExactly(Verdict.BREACH);
        }

        /**
         * Written together, in one transaction, so a report can never pair a p95 from one analysis
         * with a verdict from another — a comparison nobody made, presented as the conclusion.
         */
        @Test
        @DisplayName("measurements and verdicts are recorded together")
        void metricsAndVerdictsAreWrittenTogether() {
            catalog.recordAnalysis(run.id(), result(2, 590), outcomes(Verdict.PASS));

            assertThat(catalog.runMetricsFor(run.id())).isPresent();
            assertThat(catalog.objectiveOutcomesFor(run.id())).hasSize(1);
        }

        /**
         * A metrics row is self-describing. Reading a p95 must not require joining execution_plans to
         * find out which engine produced it — that row may be pruned long before the report is opened.
         */
        @Test
        @DisplayName("every row records the engine that produced the samples")
        void engineIsStoredOnEveryRow() {
            catalog.recordAnalysis(run.id(), result(2, 590), outcomes(Verdict.PASS));

            assertThat(jdbc.queryForList(
                    "SELECT engine FROM run_metrics WHERE run_id = ?", run.id().value()))
                    .allSatisfy(row -> assertThat(row.get("engine")).isEqualTo("jmeter"));
        }
    }

    @Nested
    @DisplayName("artifacts")
    class Artifacts {

        @Test
        @DisplayName("re-storing the same key does not double-count a run's evidence")
        void repeatedStoreIsIdempotent() {
            var reference = new com.perfplatform.domain.port.ArtifactStore.ArtifactReference(
                    com.perfplatform.domain.identity.ArtifactId.generate(), run.id(),
                    Optional.empty(), ArtifactType.RESULTS,
                    "teams/x/runs/" + run.id().value() + "/analysis/results.jtl",
                    "sha256:abc", "text/csv", 1024, NOW);

            catalog.recordArtifact(reference);
            catalog.recordArtifact(reference);

            assertThat(catalog.artifactsFor(run.id())).hasSize(1);
        }
    }
}
