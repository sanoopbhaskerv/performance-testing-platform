package com.perfplatform.orchestration.run;

import com.perfplatform.domain.identity.TestRunId;
import com.perfplatform.domain.port.TestRunRepository;
import com.perfplatform.domain.run.RunState;
import com.perfplatform.domain.run.RunTransition;
import com.perfplatform.domain.run.TestRun;
import com.perfplatform.domain.security.CallerContext;
import com.perfplatform.domain.security.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * Stops a run.
 *
 * <p>Cancellation is two steps, not one, whenever a runner might exist. The request is persisted
 * first and the runner is stopped by reconciliation afterwards. Doing it in one step would mean a
 * control-plane crash between "user pressed stop" and "runner terminated" left a load generator
 * running against a real system with nothing recording that anyone had asked it to stop.
 *
 * <p>A run that has not been provisioned yet has no runner to stop, so it goes straight to
 * {@link RunState#CANCELLED} — no reason to make someone wait a reconciliation cycle for a run that
 * was never going to start.
 *
 * <h2>Idempotency</h2>
 * Cancelling an already-cancelled run succeeds and changes nothing. Callers retry cancellation —
 * it is what people do when a stop button appears not to have worked — and a second attempt that
 * failed would push them toward blunter instruments.
 *
 * <p>Cancelling a run that <em>finished</em> is a different matter and is refused. It is not a
 * retry; it is a mistake about which run is being stopped, and silently returning success would
 * leave the caller believing they had stopped something.
 */
public final class CancelTestRunUseCase {

    private static final Logger log = LoggerFactory.getLogger(CancelTestRunUseCase.class);

    private final TestRunRepository runs;
    private final Clock clock;

    public CancelTestRunUseCase(TestRunRepository runs, Clock clock) {
        this.runs = Objects.requireNonNull(runs, "runs");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** What cancelling achieved. */
    public enum Status {

        /** The run is now cancelled; nothing was executing. */
        CANCELLED,

        /** The request is recorded; reconciliation will stop the runner and finish the job. */
        CANCELLATION_REQUESTED,

        /** The run was already cancelled. Nothing changed. */
        ALREADY_CANCELLED
    }

    public record CancellationOutcome(TestRun run, Status status) {
    }

    /** Raised when the run does not exist, or the caller may not see it. */
    public static class UnknownRunException extends RuntimeException {
        public UnknownRunException(TestRunId id) {
            super("No such test run: " + id);
        }
    }

    /** Raised when the run has finished, or has passed the point where stopping means anything. */
    public static class RunNotCancellableException extends RuntimeException {
        private final transient RunState state;

        public RunNotCancellableException(TestRunId id, RunState state, String explanation) {
            super("Run " + id + " cannot be cancelled: " + explanation);
            this.state = state;
        }

        public RunState state() {
            return state;
        }
    }

    public CancellationOutcome cancel(TestRunId runId, String reason, CallerContext caller) {
        TestRun run = runs.find(runId).orElseThrow(() -> new UnknownRunException(runId));
        caller.requireAct(Scope.RUNS_CANCEL, run.teamId());

        if (run.state() == RunState.CANCELLED) {
            return new CancellationOutcome(run, Status.ALREADY_CANCELLED);
        }
        if (run.state().isTerminal()) {
            throw new RunNotCancellableException(runId, run.state(),
                    "it has already finished with status " + run.state());
        }
        if (run.state() == RunState.ANALYZING) {
            // The load has stopped; only analysis remains. Cancelling here would discard the
            // results of a test that already ran, which is the opposite of what someone pressing
            // stop wants.
            throw new RunNotCancellableException(runId, run.state(),
                    "load generation has already finished and results are being analyzed");
        }

        Instant now = clock.instant();
        TestRun withRequest = run.isCancellationRequested()
                ? run
                : runs.apply(run.cancellationRequested(caller.subject(), reason, now));

        if (withRequest.state().mayHaveRunner()) {
            log.info("Cancellation requested runId={} teamId={} state={} subject={}",
                    runId, run.teamId(), withRequest.state(), caller.subject());
            return new CancellationOutcome(withRequest, Status.CANCELLATION_REQUESTED);
        }

        RunTransition cancelled = withRequest.transitionTo(
                RunState.CANCELLED, caller.subject(), reason, now);
        TestRun stored = runs.apply(cancelled);

        log.info("Run cancelled runId={} teamId={} previousState={} subject={}",
                runId, run.teamId(), run.state(), caller.subject());

        return new CancellationOutcome(stored, Status.CANCELLED);
    }
}
