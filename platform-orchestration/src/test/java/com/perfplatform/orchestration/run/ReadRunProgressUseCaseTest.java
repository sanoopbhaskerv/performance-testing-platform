package com.perfplatform.orchestration.run;

import com.perfplatform.domain.admission.ConcurrencyAdmissionPolicy;
import com.perfplatform.domain.admission.CapacityLimits;
import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.identity.TestDefinitionVersionId;
import com.perfplatform.domain.identity.TestRunId;
import com.perfplatform.domain.progress.LatencyHistogram;
import com.perfplatform.domain.progress.LiveProgress;
import com.perfplatform.domain.progress.ProgressInterpreter;
import com.perfplatform.domain.progress.ProgressMetrics;
import com.perfplatform.domain.results.ObjectiveOutcome;
import com.perfplatform.domain.results.OperationMetrics;
import com.perfplatform.domain.run.TestRun;
import com.perfplatform.orchestration.OrchestrationSettings;
import com.perfplatform.orchestration.support.OrchestrationFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The live view a console polls, assembled on read.
 *
 * <p>Two behaviours matter more than the assembly. A run belonging to another team must be
 * indistinguishable from one that does not exist — a live view is the read most likely to be polled
 * from a dashboard nobody logged into recently, and the one place an authorization shortcut would be
 * least noticed. And the staleness findings have to be computed against the reader's clock, because
 * "this generator went quiet ninety seconds ago" is a statement about now, not about when the
 * snapshot was written.
 */
class ReadRunProgressUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-08-04T10:05:00Z");

    private final com.perfplatform.orchestration.support.InMemoryTestRunRepository runs =
            new com.perfplatform.orchestration.support.InMemoryTestRunRepository();
    private final OrchestrationFixtures.InMemoryArtifacts artifacts =
            new OrchestrationFixtures.InMemoryArtifacts();
    private final OrchestrationFixtures.InMemoryProgress progress =
            new OrchestrationFixtures.InMemoryProgress();
    private final OrchestrationFixtures.StubDefinitionVersionCatalog versions =
            new OrchestrationFixtures.StubDefinitionVersionCatalog();

    private final TeamId team = TeamId.generate();
    private TestRun run;
    private ReadRunProgressUseCase useCase;

    @BeforeEach
    void setUp() {
        var version = versions.add(team, OrchestrationFixtures.apiSpecification(), true);
        run = runs.create(TestRun.request(TestRunId.generate(), team,
                TestDefinitionVersionId.of(version.id().value()), "performance",
                new TestRun.TriggerContext("nightly", "sha256:abc", "alice@example.com"), NOW));

        useCase = new ReadRunProgressUseCase(
                new ReadTestRunUseCase(runs, artifacts, new ConcurrencyAdmissionPolicy(),
                        CapacityLimits.DEFAULTS),
                progress,
                versions,
                OrchestrationFixtures.permissiveAnalyzer(),
                OrchestrationSettings.DEFAULTS,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("a run that has never reported has no live view, rather than one full of zeros")
    void nothingPublishedYet() {
        assertThat(useCase.read(run.id(), 100, OrchestrationFixtures.caller(team))).isEmpty();
    }

    @Test
    @DisplayName("another team's run is reported as not existing, not as forbidden")
    void refusesAnotherTeamsRun() {
        progress.record(point(NOW.minusSeconds(2), 1_000, 0, 25));

        assertThatThrownBy(() -> useCase.read(run.id(), 100,
                OrchestrationFixtures.caller(TeamId.generate())))
                .isInstanceOf(CancelTestRunUseCase.UnknownRunException.class);
    }

    @Test
    @DisplayName("the view carries the merged numbers and the series behind them")
    void returnsTheSeries() {
        progress.record(point(NOW.minusSeconds(20), 400, 0, 25));
        progress.record(point(NOW.minusSeconds(2), 1_000, 0, 25));

        var view = useCase.read(run.id(), 100, OrchestrationFixtures.caller(team)).orElseThrow();

        assertThat(view.latest().overall().sampleCount()).isEqualTo(1_000);
        assertThat(view.series()).hasSize(2);
        assertThat(view.observedAt()).isEqualTo(NOW);
    }

    /**
     * Staleness is measured on read, which is the only place it can be measured.
     *
     * <p>A snapshot recorded ninety seconds ago was perfectly current when it was written. Whether
     * it still describes anything is a question about the reader's clock, so an observation baked in
     * at write time would say "live" forever.
     */
    @Test
    @DisplayName("a generator that has gone quiet is reported against the reader's clock")
    void reportsSilenceAgainstTheReadersClock() {
        // Past OrchestrationSettings.DEFAULTS.heartbeatTolerance(), which is five minutes.
        progress.record(point(NOW.minusSeconds(400), 1_000, 0, 25));

        var view = useCase.read(run.id(), 100, OrchestrationFixtures.caller(team)).orElseThrow();

        assertThat(view.observations())
                .extracting(observation -> observation.code())
                .contains(ProgressInterpreter.RUNNER_SILENT);
    }

    @Test
    @DisplayName("objectives are judged live, and every one is named as provisional")
    void judgesObjectivesLive() {
        progress.record(point(NOW.minusSeconds(2), 1_000, 0, 25));

        var view = useCase.read(run.id(), 100, OrchestrationFixtures.caller(team)).orElseThrow();

        // The fixture analyzer judges nothing; what this pins is that the field exists, is separate
        // from the run's stored objectives, and is named so that no caller can mistake one for the
        // other.
        assertThat(view.provisionalObjectives()).isNotNull();
        assertThat(view.observations()).isNotEmpty();
    }

    @Test
    @DisplayName("a breaching live objective is surfaced as an observation, with its caveat")
    void surfacesBreachingObjectives() {
        progress.record(point(NOW.minusSeconds(2), 1_000, 0, 25));

        var breach = new ObjectiveOutcome("http.response.time@*", "http.response.time", "*",
                ObjectiveOutcome.Verdict.BREACH, 900, 500,
                "p95 was 900ms against a ceiling of 500ms.");

        assertThat(ProgressInterpreter.interpret(
                        progress.series(run.id(), 10), List.of(breach), NOW,
                        OrchestrationSettings.DEFAULTS.heartbeatTolerance()))
                .anySatisfy(observation -> {
                    assertThat(observation.code()).isEqualTo(ProgressInterpreter.OBJECTIVE_AT_RISK);
                    assertThat(observation.evidence()).contains("not the run's verdict");
                });
    }

    private LiveProgress point(Instant capturedAt, long samples, long errors, long activeUsers) {
        long[] latencies = new long[(int) samples];
        java.util.Arrays.fill(latencies, 120L);

        ProgressMetrics overall = new ProgressMetrics(OperationMetrics.ALL, samples, errors, 0,
                10, 900, 120, 20_000, LatencyHistogram.of(latencies));

        return new LiveProgress(run.id(), "jmeter", capturedAt, capturedAt, 1, 120, activeUsers,
                overall, Map.of(),
                List.of(new LiveProgress.ShardReport(0, capturedAt, samples, errors, activeUsers)));
    }
}
