package com.perfplatform.characterization;

import com.perfplatform.storage.TestRun;
import com.perfplatform.storage.TestRunRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Characterization tests for V1 run persistence.
 *
 * <p>Pins the storage contract that Increment 1C replaces with Flyway-managed tables, an
 * append-only run event log, optimistic locking and a {@code (team_id, idempotency_key)}
 * uniqueness constraint (CLAUDE.md §12). Notably V1 has no version column, no idempotency
 * key and no run event table, so concurrent writers silently overwrite one another.
 */
@DataJpaTest
@TestPropertySource(properties = {
        // Increment 1B moved schema ownership from Hibernate to Flyway. Running these V1
        // characterization tests against the Flyway-created schema is what proves the baseline
        // migration reproduces the table the V1 entity expects — the risk the ddl-auto swap
        // carries. Letting Hibernate build the schema here would test the entity against itself
        // and prove nothing about the migration.
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration/{vendor}"
})
class TestRunPersistenceCharacterizationTest {

    @Autowired
    private TestRunRepository repository;

    private TestRun newRun(String team, TestRun.TestType type) {
        TestRun run = new TestRun();
        run.setTeam(team);
        run.setTestType(type);
        return run;
    }

    @Test
    @DisplayName("a saved run is assigned a generated UUID and defaults to PENDING/UNKNOWN")
    void assignsGeneratedIdAndDefaults() {
        TestRun saved = repository.save(newRun("alpha", TestRun.TestType.LOAD));

        assertThat(saved.getId()).isNotBlank();
        assertThat(java.util.UUID.fromString(saved.getId())).isNotNull();
        assertThat(saved.getStatus()).isEqualTo(TestRun.Status.PENDING);
        assertThat(saved.getSlaResult()).isEqualTo(TestRun.SlaResult.UNKNOWN);
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("run status, SLA metrics and failure reason round-trip through the database")
    void persistsRunOutcome() {
        TestRun run = newRun("alpha", TestRun.TestType.LOAD);
        Instant started = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        run.setStatus(TestRun.Status.COMPLETED);
        run.setSlaResult(TestRun.SlaResult.BREACH);
        run.setP95LatencyMs(742L);
        run.setErrorRatePercent(2.5);
        run.setStartedAt(started);
        run.setCompletedAt(started.plusSeconds(300));
        run.setReportPath("/tmp/perf-platform/run/perf-report.html");
        run.setFailureReason("P95 latency 742ms > threshold 500ms");

        String id = repository.saveAndFlush(run).getId();
        TestRun reloaded = repository.findById(id).orElseThrow();

        assertThat(reloaded.getStatus()).isEqualTo(TestRun.Status.COMPLETED);
        assertThat(reloaded.getSlaResult()).isEqualTo(TestRun.SlaResult.BREACH);
        assertThat(reloaded.getP95LatencyMs()).isEqualTo(742L);
        assertThat(reloaded.getErrorRatePercent()).isEqualTo(2.5);
        assertThat(reloaded.getStartedAt()).isEqualTo(started);
        assertThat(reloaded.getCompletedAt()).isEqualTo(started.plusSeconds(300));
        assertThat(reloaded.getReportPath()).isEqualTo("/tmp/perf-platform/run/perf-report.html");
        assertThat(reloaded.getFailureReason()).isEqualTo("P95 latency 742ms > threshold 500ms");
    }

    @Test
    @DisplayName("enums are persisted as strings, not ordinals")
    void persistsEnumsAsStrings() {
        TestRun run = newRun("alpha", TestRun.TestType.ENDURANCE);
        run.setStatus(TestRun.Status.RUNNING);
        String id = repository.saveAndFlush(run).getId();

        TestRun reloaded = repository.findById(id).orElseThrow();

        assertThat(reloaded.getTestType()).isEqualTo(TestRun.TestType.ENDURANCE);
        assertThat(reloaded.getStatus()).isEqualTo(TestRun.Status.RUNNING);
    }

    @Test
    @DisplayName("history is scoped to a team and ordered newest first")
    void findsHistoryByTeamNewestFirst() {
        repository.saveAndFlush(withCreatedAt(newRun("alpha", TestRun.TestType.LOAD), 3));
        TestRun newest = repository.saveAndFlush(withCreatedAt(newRun("alpha", TestRun.TestType.SPIKE), 1));
        repository.saveAndFlush(withCreatedAt(newRun("beta", TestRun.TestType.LOAD), 2));

        List<TestRun> history = repository.findByTeamOrderByCreatedAtDesc("alpha");

        assertThat(history).hasSize(2);
        assertThat(history.get(0).getId()).isEqualTo(newest.getId());
        assertThat(history).allSatisfy(run -> assertThat(run.getTeam()).isEqualTo("alpha"));
    }

    @Test
    @DisplayName("history can be filtered by team and test type together")
    void findsHistoryByTeamAndType() {
        repository.saveAndFlush(newRun("alpha", TestRun.TestType.LOAD));
        repository.saveAndFlush(newRun("alpha", TestRun.TestType.ENDURANCE));

        List<TestRun> loadRuns =
                repository.findByTeamAndTestTypeOrderByCreatedAtDesc("alpha", TestRun.TestType.LOAD);

        assertThat(loadRuns).hasSize(1);
        assertThat(loadRuns.get(0).getTestType()).isEqualTo(TestRun.TestType.LOAD);
    }

    @Test
    @DisplayName("the recent-runs query caps results at ten")
    void capsRecentRunsAtTen() {
        for (int i = 0; i < 12; i++) {
            repository.saveAndFlush(withCreatedAt(newRun("alpha", TestRun.TestType.LOAD), i));
        }

        assertThat(repository.findRecentByTeam("alpha")).hasSize(10);
    }

    @Test
    @DisplayName("running runs can be listed, which is the only V1 recovery signal")
    void findsRunningRuns() {
        TestRun running = newRun("alpha", TestRun.TestType.LOAD);
        running.setStatus(TestRun.Status.RUNNING);
        repository.saveAndFlush(running);
        repository.saveAndFlush(newRun("alpha", TestRun.TestType.LOAD));

        List<TestRun> found = repository.findAllRunning();

        assertThat(found).hasSize(1);
        assertThat(found.get(0).getStatus()).isEqualTo(TestRun.Status.RUNNING);
    }

    /**
     * KNOWN GAP (CLAUDE.md §12): {@code test_runs} has no optimistic-lock version, no
     * idempotency key, no execution plan reference and no cancellation metadata. Increment 1C
     * adds them via Flyway. This test documents the current column set so the migration is a
     * deliberate, reviewed change rather than an incidental Hibernate schema update.
     */
    @Test
    @DisplayName("KNOWN GAP: the V1 entity has no version, idempotency key or plan reference")
    void hasNoConcurrencyOrIdempotencyColumns() {
        List<String> fields = java.util.Arrays.stream(TestRun.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName)
                .toList();

        assertThat(fields).doesNotContain("version", "idempotencyKey", "executionPlanId",
                "definitionVersionId", "cancelledAt", "cancelledBy");
    }

    private TestRun withCreatedAt(TestRun run, int minutesAgo) {
        try {
            java.lang.reflect.Field field = TestRun.class.getDeclaredField("createdAt");
            field.setAccessible(true);
            field.set(run, Instant.now().minus(minutesAgo, ChronoUnit.MINUTES));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return run;
    }
}
