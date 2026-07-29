package com.perfplatform.orchestration.run;

import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.identity.TestDefinitionVersionId;
import com.perfplatform.domain.identity.TestRunId;
import com.perfplatform.domain.run.RunEventType;
import com.perfplatform.domain.run.RunState;
import com.perfplatform.domain.run.TestRun;
import com.perfplatform.domain.security.CallerContext;
import com.perfplatform.domain.security.Scope;
import com.perfplatform.orchestration.run.CancelTestRunUseCase.CancellationOutcome;
import com.perfplatform.orchestration.run.CancelTestRunUseCase.Status;
import com.perfplatform.orchestration.support.InMemoryTestRunRepository;
import com.perfplatform.orchestration.support.OrchestrationFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Cancelling a run.
 *
 * <p>Idempotency is a usability property with a safety consequence: people retry a stop button that
 * appears not to have worked, and a retry that fails pushes them toward blunter instruments —
 * deleting the Job by hand, or restarting the control plane.
 */
class CancelTestRunUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-07-29T10:00:00Z");

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final InMemoryTestRunRepository runs = new InMemoryTestRunRepository();

    private CancelTestRunUseCase useCase;
    private TeamId team;
    private CallerContext caller;

    @BeforeEach
    void setUp() {
        useCase = new CancelTestRunUseCase(runs, clock);
        team = TeamId.generate();
        caller = OrchestrationFixtures.caller(team);
    }

    /** Seeds a run already in the given state, so each test starts where it needs to. */
    private TestRun seed(RunState state) {
        TestRun run = TestRun.request(TestRunId.generate(), team,
                TestDefinitionVersionId.generate(), "performance",
                new TestRun.TriggerContext("key-" + state, "sha256:abc", "alice@example.com"),
                NOW).run();

        TestRun positioned = switch (state) {
            case VALIDATING -> run;
            case QUEUED -> run.transitionTo(RunState.QUEUED, "alice", null, NOW).run();
            case PROVISIONING -> run.transitionTo(RunState.QUEUED, "alice", null, NOW).run()
                    .transitionTo(RunState.PROVISIONING, "system", null, NOW).run();
            case RUNNING -> run.transitionTo(RunState.QUEUED, "alice", null, NOW).run()
                    .transitionTo(RunState.PROVISIONING, "system", null, NOW).run()
                    .transitionTo(RunState.RUNNING, "system", null, NOW).run();
            case ANALYZING -> run.transitionTo(RunState.QUEUED, "alice", null, NOW).run()
                    .transitionTo(RunState.PROVISIONING, "system", null, NOW).run()
                    .transitionTo(RunState.RUNNING, "system", null, NOW).run()
                    .transitionTo(RunState.ANALYZING, "system", null, NOW).run();
            case COMPLETED -> run.transitionTo(RunState.QUEUED, "alice", null, NOW).run()
                    .transitionTo(RunState.PROVISIONING, "system", null, NOW).run()
                    .transitionTo(RunState.RUNNING, "system", null, NOW).run()
                    .transitionTo(RunState.ANALYZING, "system", null, NOW).run()
                    .transitionTo(RunState.COMPLETED, "system", null, NOW).run();
            case CANCELLED -> run.transitionTo(RunState.CANCELLED, "alice", null, NOW).run();
            default -> throw new IllegalArgumentException("Unsupported seed state: " + state);
        };

        runs.seed(positioned);
        return positioned;
    }

    @Nested
    @DisplayName("runs with no runner yet")
    class NoRunner {

        /**
         * Nothing is executing, so there is no reason to make someone wait a reconciliation cycle
         * for a run that was never going to start.
         */
        @Test
        @DisplayName("a queued run is cancelled immediately")
        void queuedCancelsImmediately() {
            TestRun run = seed(RunState.QUEUED);

            CancellationOutcome outcome = useCase.cancel(run.id(), "Wrong target", caller);

            assertThat(outcome.status()).isEqualTo(Status.CANCELLED);
            assertThat(outcome.run().state()).isEqualTo(RunState.CANCELLED);
            assertThat(outcome.run().timeline().completedAt()).contains(NOW);
        }

        @Test
        @DisplayName("a validating run can be stopped before it is ever admitted")
        void validatingCancelsImmediately() {
            TestRun run = seed(RunState.VALIDATING);

            assertThat(useCase.cancel(run.id(), null, caller).status()).isEqualTo(Status.CANCELLED);
        }

        @Test
        @DisplayName("the request and the transition are both recorded")
        void bothEventsRecorded() {
            TestRun run = seed(RunState.QUEUED);
            useCase.cancel(run.id(), "Wrong target", caller);

            assertThat(runs.events(run.id(), 0, 10))
                    .extracting(event -> event.type().name())
                    .containsExactly(
                            RunEventType.CANCELLATION_REQUESTED.name(),
                            RunEventType.STATE_CHANGED.name());
        }
    }

    @Nested
    @DisplayName("runs with a live runner")
    class LiveRunner {

        /**
         * Two steps, because a running workload cannot stop on request alone. Persisting the desire
         * first means a control-plane crash in the interval still results in the run being stopped.
         */
        @Test
        @DisplayName("a running run records the request and waits for the runner to stop")
        void runningRecordsRequest() {
            TestRun run = seed(RunState.RUNNING);

            CancellationOutcome outcome = useCase.cancel(run.id(), "Wrong target", caller);

            assertThat(outcome.status()).isEqualTo(Status.CANCELLATION_REQUESTED);
            assertThat(outcome.run().state()).isEqualTo(RunState.RUNNING);
            assertThat(outcome.run().isCancellationRequested()).isTrue();
        }

        @Test
        @DisplayName("a provisioning run also records the request rather than stopping outright")
        void provisioningRecordsRequest() {
            TestRun run = seed(RunState.PROVISIONING);

            assertThat(useCase.cancel(run.id(), null, caller).status())
                    .isEqualTo(Status.CANCELLATION_REQUESTED);
        }

        @Test
        @DisplayName("repeating the request does not record a second one")
        void repeatDoesNotDuplicateRequest() {
            TestRun run = seed(RunState.RUNNING);

            useCase.cancel(run.id(), "Wrong target", caller);
            useCase.cancel(run.id(), "Wrong target", caller);

            assertThat(runs.events(run.id(), 0, 10))
                    .filteredOn(event -> event.type() == RunEventType.CANCELLATION_REQUESTED)
                    .hasSize(1);
        }
    }

    @Nested
    @DisplayName("idempotency and refusals")
    class IdempotencyAndRefusals {

        @Test
        @DisplayName("cancelling an already-cancelled run succeeds and changes nothing")
        void alreadyCancelledIsIdempotent() {
            TestRun run = seed(RunState.CANCELLED);

            CancellationOutcome outcome = useCase.cancel(run.id(), "again", caller);

            assertThat(outcome.status()).isEqualTo(Status.ALREADY_CANCELLED);
            assertThat(outcome.run().state()).isEqualTo(RunState.CANCELLED);
            assertThat(runs.events(run.id(), 0, 10)).isEmpty();
        }

        /**
         * Not a retry but a mistake about which run is being stopped. Silently returning success
         * would leave the caller believing they had stopped something.
         */
        @Test
        @DisplayName("cancelling a completed run is refused")
        void completedIsRefused() {
            TestRun run = seed(RunState.COMPLETED);

            assertThatThrownBy(() -> useCase.cancel(run.id(), null, caller))
                    .isInstanceOf(CancelTestRunUseCase.RunNotCancellableException.class)
                    .hasMessageContaining("already finished");
        }

        @Test
        @DisplayName("cancelling during analysis is refused, since the load has already stopped")
        void analyzingIsRefused() {
            TestRun run = seed(RunState.ANALYZING);

            assertThatThrownBy(() -> useCase.cancel(run.id(), null, caller))
                    .isInstanceOf(CancelTestRunUseCase.RunNotCancellableException.class)
                    .hasMessageContaining("already finished");
        }

        @Test
        @DisplayName("an unknown run is refused")
        void unknownRunIsRefused() {
            assertThatThrownBy(() -> useCase.cancel(TestRunId.generate(), null, caller))
                    .isInstanceOf(CancelTestRunUseCase.UnknownRunException.class);
        }
    }

    @Nested
    @DisplayName("authorization")
    class Authorization {

        @Test
        @DisplayName("another team cannot cancel this team's run")
        void otherTeamRefused() {
            TestRun run = seed(RunState.RUNNING);
            CallerContext outsider = OrchestrationFixtures.caller(TeamId.generate());

            assertThatThrownBy(() -> useCase.cancel(run.id(), null, outsider))
                    .isInstanceOf(CallerContext.AuthorizationException.class);

            assertThat(runs.find(run.id()).orElseThrow().isCancellationRequested()).isFalse();
        }

        @Test
        @DisplayName("runs:cancel is required, separately from runs:execute")
        void cancelScopeRequired() {
            TestRun run = seed(RunState.RUNNING);
            CallerContext executeOnly = new CallerContext("ci@example.com", Set.of(team),
                    Set.of(Scope.RUNS_EXECUTE, Scope.RUNS_READ), true);

            assertThatThrownBy(() -> useCase.cancel(run.id(), null, executeOnly))
                    .isInstanceOf(CallerContext.AuthorizationException.class);
        }
    }
}
