package com.perfplatform.orchestration.run;

import com.perfplatform.domain.admission.AdmissionDecision;
import com.perfplatform.domain.admission.AdmissionPolicy;
import com.perfplatform.domain.admission.CapacityLimits;
import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.identity.TestRunId;
import com.perfplatform.domain.port.ArtifactStore;
import com.perfplatform.domain.port.ResultCatalog;
import com.perfplatform.domain.port.TestRunRepository;
import com.perfplatform.domain.results.ObjectiveOutcome;
import com.perfplatform.domain.run.RunEvent;
import com.perfplatform.domain.run.RunState;
import com.perfplatform.domain.run.TestRun;
import com.perfplatform.domain.security.CallerContext;
import com.perfplatform.domain.security.Scope;
import com.perfplatform.orchestration.run.CancelTestRunUseCase.UnknownRunException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Reads runs and their history, subject to team authorization.
 *
 * <p>A use case rather than a controller calling the repository, because every read here is an
 * authorization decision. Run identifiers appear in URLs, pipeline logs and chat messages, so a
 * read path that trusted the identifier alone would let anyone holding one read another team's
 * results — which is the V1 behavior CLAUDE.md §5 requires replacing.
 */
public final class ReadTestRunUseCase {

    /** Bound on one page of history, so a long-running test cannot be asked for in one response. */
    private static final int MAX_EVENTS = 500;

    /** Bound on one page of runs, so a long-lived team cannot be asked for in one response. */
    private static final int MAX_RUNS = 100;

    private final TestRunRepository runs;
    private final ResultCatalog results;
    private final AdmissionPolicy admissionPolicy;
    private final CapacityLimits limits;

    public ReadTestRunUseCase(TestRunRepository runs, ResultCatalog results,
            AdmissionPolicy admissionPolicy, CapacityLimits limits) {
        this.runs = Objects.requireNonNull(runs, "runs");
        this.results = Objects.requireNonNull(results, "results");
        this.admissionPolicy = Objects.requireNonNull(admissionPolicy, "admissionPolicy");
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    /**
     * The caller's teams' runs, newest first.
     *
     * <p>Scoped to the teams the caller belongs to rather than filtered afterwards: a listing that
     * fetched everything and then removed what the caller may not see is one refactor away from
     * forgetting the second step.
     */
    public List<TestRun> listForCaller(CallerContext caller, int limit) {
        List<TestRun> visible = new ArrayList<>();
        for (TeamId teamId : caller.teamIds()) {
            if (caller.canAct(Scope.RUNS_READ, teamId)) {
                visible.addAll(runs.findByTeam(teamId, Math.min(Math.max(limit, 1), MAX_RUNS)));
            }
        }
        visible.sort(Comparator.comparing((TestRun run) -> run.timeline().requestedAt()).reversed());
        return List.copyOf(visible);
    }

    /** A run's stored objective verdicts. */
    public List<ObjectiveOutcome> objectives(TestRunId runId, CallerContext caller) {
        get(runId, caller);
        return results.objectiveOutcomesFor(runId);
    }

    /** A run's stored artifacts. References only — content is served through a download grant. */
    public List<ArtifactStore.ArtifactReference> artifacts(TestRunId runId, CallerContext caller) {
        get(runId, caller);
        return results.artifactsFor(runId);
    }

    public TestRun get(TestRunId runId, CallerContext caller) {
        TestRun run = runs.find(runId).orElseThrow(() -> new UnknownRunException(runId));
        // Authorization failure and non-existence deliberately produce the same outcome at the API
        // boundary, so a caller cannot probe which run identifiers are real.
        caller.requireAct(Scope.RUNS_READ, run.teamId());
        return run;
    }

    /**
     * A run's history.
     *
     * @param afterSequence events after this sequence; 0 for the whole history
     */
    public List<RunEvent> events(TestRunId runId, long afterSequence, int limit, CallerContext caller) {
        get(runId, caller);
        return runs.events(runId, afterSequence, Math.min(Math.max(limit, 1), MAX_EVENTS));
    }

    /**
     * Why a queued run has not started yet.
     *
     * <p>Computed live rather than read from an event, because it changes as capacity does: a run
     * deferred an hour ago for a team limit may now be waiting on the global one, and showing the
     * stale reason would send someone to look at the wrong thing.
     *
     * @return empty unless the run is queued
     */
    public Optional<AdmissionDecision> queueReason(TestRunId runId, CallerContext caller) {
        TestRun run = get(runId, caller);
        if (run.state() != RunState.QUEUED) {
            return Optional.empty();
        }
        return Optional.of(admissionPolicy.evaluate(run.teamId(), limits, runs.currentCapacity()));
    }
}
