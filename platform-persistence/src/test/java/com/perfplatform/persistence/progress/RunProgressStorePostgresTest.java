package com.perfplatform.persistence.progress;

import com.perfplatform.domain.identity.ApplicationId;
import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.identity.TestDefinitionId;
import com.perfplatform.domain.identity.TestDefinitionVersionId;
import com.perfplatform.domain.identity.TestRunId;
import com.perfplatform.domain.progress.LatencyHistogram;
import com.perfplatform.domain.progress.LiveProgress;
import com.perfplatform.domain.progress.ProgressMetrics;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A run's live snapshots, against real PostgreSQL.
 *
 * <p>Three things are asserted here and nowhere else, because all three are enforced by the database
 * rather than by the code above it: the merged document survives the round trip exactly, a repeated
 * reconciliation pass does not append a duplicate point, and a series longer than the requested
 * window still keeps its oldest point — which is what the trend rules compare the present against.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RunProgressStorePostgresTest {

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

    private static final Instant NOW = Instant.parse("2026-08-04T10:00:00Z");

    private static final String SPECIFICATION = """
            {"apiVersion":"performance.ameriprise.com/v1alpha1","kind":"PerformanceTest",\
            "metadata":{"name":"proposal-baseline","team":"advice","application":"proposal"}}""";

    @Autowired
    private RunProgressStore progress;

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

    private TestRun run;

    @BeforeEach
    void seed() {
        new JdbcTemplate(dataSource).execute("""
                TRUNCATE TABLE run_progress, run_metrics, objective_results, artifacts, run_events,
                    runner_shards, test_runs, execution_plans, test_definition_versions,
                    test_definitions, applications, teams
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

    /**
     * The round trip has to be exact, including the latency buckets.
     *
     * <p>The buckets are the whole reason the live view can add shards together. A stored
     * distribution that came back with one count off would produce a percentile that is wrong in a
     * way nothing else could detect — the number would still look entirely plausible.
     */
    @Test
    @DisplayName("what the reconciler merged is exactly what a read returns")
    void roundTripsExactly() {
        LiveProgress recorded = snapshot(NOW.plusSeconds(30), 1_000, 20, 25);

        progress.record(recorded);

        LiveProgress read = progress.latest(run.id()).orElseThrow();

        assertThat(read).isEqualTo(recorded);
        assertThat(read.overall().latency().counts())
                .isEqualTo(recorded.overall().latency().counts());
        assertThat(read.byOperation()).containsOnlyKeys("checkout/browse");
        assertThat(read.shards()).hasSize(2);
    }

    @Test
    @DisplayName("a repeated pass over the same runner snapshot does not add a second point")
    void deduplicatesByCaptureInstant() {
        LiveProgress recorded = snapshot(NOW.plusSeconds(30), 1_000, 20, 25);

        progress.record(recorded);
        progress.record(recorded);

        assertThat(progress.series(run.id(), 100)).hasSize(1);
    }

    @Test
    @DisplayName("the series comes back oldest first, so a trend reads left to right")
    void seriesIsChronological() {
        progress.record(snapshot(NOW.plusSeconds(10), 100, 0, 10));
        progress.record(snapshot(NOW.plusSeconds(20), 400, 1, 20));
        progress.record(snapshot(NOW.plusSeconds(30), 900, 4, 30));

        assertThat(progress.series(run.id(), 100))
                .extracting(point -> point.overall().sampleCount())
                .containsExactly(100L, 400L, 900L);
    }

    /**
     * A window that has forgotten the start of the run cannot say anything has changed.
     *
     * <p>The trend rules — latency past a knee, throughput plateaued, errors concentrated in the
     * recent window — all compare the present against the beginning. A naive tail would make a run
     * that has been degrading for twenty minutes look like a run that is merely slow.
     */
    @Test
    @DisplayName("a capped window keeps the oldest point, not just the most recent ones")
    void cappedWindowKeepsTheBeginning() {
        for (int index = 0; index < 6; index++) {
            progress.record(snapshot(NOW.plusSeconds(10L * index), 100L * (index + 1), 0, 10));
        }

        List<LiveProgress> window = progress.series(run.id(), 3);

        assertThat(window).hasSize(3);
        assertThat(window.get(0).capturedAt()).isEqualTo(NOW);
        assertThat(window.get(window.size() - 1).capturedAt()).isEqualTo(NOW.plusSeconds(50));
    }

    @Test
    @DisplayName("a run that never reported has no live view, rather than an empty one")
    void noSnapshotsIsEmpty() {
        assertThat(progress.latest(run.id())).isEmpty();
        assertThat(progress.series(run.id(), 100)).isEmpty();
    }

    @Test
    @DisplayName("clearing removes the series, which is derived and therefore disposable")
    void clears() {
        progress.record(snapshot(NOW.plusSeconds(30), 1_000, 20, 25));

        progress.clear(run.id());

        assertThat(progress.latest(run.id())).isEmpty();
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private LiveProgress snapshot(Instant capturedAt, long samples, long errors, long activeUsers) {
        long[] latencies = new long[(int) Math.min(samples, 200)];
        java.util.Arrays.fill(latencies, 120L);

        ProgressMetrics overall = new ProgressMetrics(OperationMetrics.ALL, samples, errors, 3,
                12, 940, 180, 41_500, LatencyHistogram.of(latencies));

        Map<String, ProgressMetrics> byOperation = new LinkedHashMap<>();
        byOperation.put("checkout/browse", new ProgressMetrics("checkout/browse", samples, errors, 0,
                12, 940, 180, 41_500, LatencyHistogram.of(latencies)));

        return new LiveProgress(run.id(), "jmeter", capturedAt, capturedAt.plusSeconds(1), 2,
                capturedAt.getEpochSecond() - NOW.getEpochSecond(), activeUsers, overall, byOperation,
                List.of(new LiveProgress.ShardReport(0, capturedAt, samples / 2, errors / 2,
                                activeUsers / 2),
                        new LiveProgress.ShardReport(1, capturedAt, samples / 2, errors / 2,
                                activeUsers / 2)));
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
