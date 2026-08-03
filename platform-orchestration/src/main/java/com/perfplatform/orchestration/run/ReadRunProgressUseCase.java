package com.perfplatform.orchestration.run;

import com.perfplatform.domain.identity.TestRunId;
import com.perfplatform.domain.port.DefinitionVersionCatalog;
import com.perfplatform.domain.port.ProgressCatalog;
import com.perfplatform.domain.port.RunAnalyzer;
import com.perfplatform.domain.progress.LiveProgress;
import com.perfplatform.domain.progress.ProgressInterpreter;
import com.perfplatform.domain.progress.ProgressObservation;
import com.perfplatform.domain.results.ObjectiveOutcome;
import com.perfplatform.domain.run.TestRun;
import com.perfplatform.domain.security.CallerContext;
import com.perfplatform.domain.spec.PerformanceTestSpecification;
import com.perfplatform.orchestration.OrchestrationSettings;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What a run is doing right now, for whoever is entitled to see it.
 *
 * <p>Assembled on read rather than stored. Only the merged numbers are durable; the objective
 * outcomes and the interpretation are recomputed every time, for two reasons. Staleness is one of
 * the findings — "this generator went quiet ninety seconds ago" is a statement about the reader's
 * clock, not the writer's — and a rule that improves should improve for runs already recorded rather
 * than only for the next one.
 *
 * <h2>Everything here is provisional</h2>
 * The measurements are reduced from partial runner output with bucketed percentiles, and shards
 * report at slightly different moments. A run's verdict is what the analyzer computes from the
 * complete raw results after every shard has finished. Nothing this returns is ever stored as that,
 * and the objective outcomes carry the caveat in their own detail text so it survives being quoted
 * out of the response.
 */
public final class ReadRunProgressUseCase {

    /** Bound on a returned series, so a long run cannot be asked for in one response. */
    private static final int MAX_SERIES_POINTS = 240;

    private final ReadTestRunUseCase runs;
    private final ProgressCatalog progress;
    private final DefinitionVersionCatalog versions;
    private final RunAnalyzer analyzer;
    private final OrchestrationSettings settings;
    private final Clock clock;

    public ReadRunProgressUseCase(ReadTestRunUseCase runs, ProgressCatalog progress,
            DefinitionVersionCatalog versions, RunAnalyzer analyzer, OrchestrationSettings settings,
            Clock clock) {
        this.runs = Objects.requireNonNull(runs, "runs");
        this.progress = Objects.requireNonNull(progress, "progress");
        this.versions = Objects.requireNonNull(versions, "versions");
        this.analyzer = Objects.requireNonNull(analyzer, "analyzer");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * A run's live view.
     *
     * @param latest                the most recent merged snapshot
     * @param series                the retained history, oldest first, for showing shape over time
     * @param provisionalObjectives each objective judged against the latest snapshot. Never the
     *                              run's verdict — the name says so, and so does each outcome
     * @param observations          what the rules make of it, most severe first
     * @param observedAt            the clock the staleness in {@code observations} was measured
     *                              against, so a reader can tell a stale response from a stale run
     */
    public record LiveView(
            LiveProgress latest,
            List<LiveProgress> series,
            List<ObjectiveOutcome> provisionalObjectives,
            List<ProgressObservation> observations,
            Instant observedAt) {

        public LiveView {
            Objects.requireNonNull(latest, "latest");
            series = series == null ? List.of() : List.copyOf(series);
            provisionalObjectives = provisionalObjectives == null
                    ? List.of() : List.copyOf(provisionalObjectives);
            observations = observations == null ? List.of() : List.copyOf(observations);
        }
    }

    /**
     * Reads it, authorizing the run first.
     *
     * @return empty when no runner has published anything yet. Distinct from a view full of zeros,
     *         which would read as "measured nothing" rather than as "not measured yet"
     */
    public Optional<LiveView> read(TestRunId runId, int limit, CallerContext caller) {
        TestRun run = runs.get(runId, caller);

        List<LiveProgress> series = progress.series(runId,
                Math.min(Math.max(limit, 1), MAX_SERIES_POINTS));
        if (series.isEmpty()) {
            return Optional.empty();
        }

        LiveProgress latest = series.get(series.size() - 1);
        Instant now = clock.instant();

        List<ObjectiveOutcome> provisional = analyzer.judge(
                latest.asNormalizedResult(), objectivesOf(run));

        return Optional.of(new LiveView(
                latest,
                series,
                provisional,
                // The same tolerance the reconciler uses to declare a shard lost. Two definitions of
                // "this runner is gone" would eventually disagree, and a live view that called a
                // shard silent while the reconciler still counted it as healthy — or the reverse —
                // is worse than either answer alone.
                ProgressInterpreter.interpret(series, provisional, now,
                        settings.heartbeatTolerance()),
                now));
    }

    /**
     * The objectives the run's definition version declares.
     *
     * <p>Read from the immutable version the run references, never from the definition's current
     * head: a definition edited mid-run would otherwise have the live view judging traffic against
     * thresholds the running test was never approved with.
     */
    private List<PerformanceTestSpecification.ObjectiveSpec> objectivesOf(TestRun run) {
        return versions.find(run.definitionVersionId())
                .map(DefinitionVersionCatalog.DefinitionVersion::specification)
                .filter(specification -> specification.spec() != null)
                .map(specification -> specification.spec().objectives())
                .orElseGet(List::of);
    }
}
