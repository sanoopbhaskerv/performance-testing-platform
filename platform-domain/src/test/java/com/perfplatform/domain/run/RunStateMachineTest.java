package com.perfplatform.domain.run;

import com.perfplatform.domain.identity.TestRunId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The transition matrix from CLAUDE.md §11.
 *
 * <p>This is the contract every other component relies on, so it is asserted directly rather than
 * inferred from the behaviour of things that use it. V1's defect was that status was a field any
 * code path could assign; these tests are what stop that returning.
 */
class RunStateMachineTest {

    private static final TestRunId RUN = TestRunId.generate();

    @Nested
    @DisplayName("permitted transitions")
    class Permitted {

        @Test
        @DisplayName("validation may reject, await approval, queue or cancel")
        void fromValidating() {
            assertThat(RunStateMachine.nextStates(RunState.VALIDATING))
                    .containsExactlyInAnyOrder(RunState.REJECTED, RunState.AWAITING_APPROVAL,
                            RunState.QUEUED, RunState.CANCELLED);
        }

        @Test
        @DisplayName("an approval that never comes can expire")
        void fromAwaitingApproval() {
            assertThat(RunStateMachine.nextStates(RunState.AWAITING_APPROVAL))
                    .containsExactlyInAnyOrder(RunState.QUEUED, RunState.REJECTED,
                            RunState.CANCELLED, RunState.EXPIRED);
        }

        @Test
        @DisplayName("a queued run provisions, is cancelled, or expires waiting")
        void fromQueued() {
            assertThat(RunStateMachine.nextStates(RunState.QUEUED))
                    .containsExactlyInAnyOrder(RunState.PROVISIONING, RunState.CANCELLED,
                            RunState.EXPIRED);
        }

        @Test
        @DisplayName("provisioning can fail, because creating a runner can fail")
        void fromProvisioning() {
            assertThat(RunStateMachine.nextStates(RunState.PROVISIONING))
                    .containsExactlyInAnyOrder(RunState.RUNNING, RunState.FAILED, RunState.CANCELLED);
        }

        @Test
        @DisplayName("a running test analyzes, fails or is cancelled")
        void fromRunning() {
            assertThat(RunStateMachine.nextStates(RunState.RUNNING))
                    .containsExactlyInAnyOrder(RunState.ANALYZING, RunState.FAILED, RunState.CANCELLED);
        }

        /**
         * The load has already stopped by this point, so there is nothing left to stop, and
         * discarding the results would throw away evidence the test already paid for.
         */
        @Test
        @DisplayName("analysis cannot be cancelled — only completed or failed")
        void analysisCannotBeCancelled() {
            assertThat(RunStateMachine.nextStates(RunState.ANALYZING))
                    .containsExactlyInAnyOrder(RunState.COMPLETED, RunState.FAILED);
        }
    }

    @Nested
    @DisplayName("forbidden transitions")
    class Forbidden {

        @ParameterizedTest
        @EnumSource(value = RunState.class,
                names = {"COMPLETED", "FAILED", "CANCELLED", "REJECTED", "EXPIRED"})
        @DisplayName("a terminal run never transitions again without administrative recovery")
        void terminalStatesAreFinal(RunState terminal) {
            assertThat(terminal.isTerminal()).isTrue();
            assertThat(RunStateMachine.nextStates(terminal)).isEmpty();

            for (RunState target : RunState.values()) {
                assertThat(RunStateMachine.canTransition(terminal, target))
                        .as("%s must not transition to %s", terminal, target)
                        .isFalse();
            }
        }

        /**
         * Every run passes through provisioning so the external runner handle is persisted before
         * the platform believes load is being generated. Without that ordering a crash between the
         * two leaves an orphaned load generator that nothing can find or cancel.
         */
        @Test
        @DisplayName("a queued run cannot skip provisioning and go straight to running")
        void queuedCannotSkipProvisioning() {
            assertThat(RunStateMachine.canTransition(RunState.QUEUED, RunState.RUNNING)).isFalse();

            assertThatThrownBy(() ->
                    RunStateMachine.requireTransition(RUN, RunState.QUEUED, RunState.RUNNING))
                    .isInstanceOf(InvalidRunTransitionException.class)
                    .hasMessageContaining("PROVISIONING");
        }

        @Test
        @DisplayName("a run cannot go backwards")
        void cannotMoveBackwards() {
            assertThat(RunStateMachine.canTransition(RunState.RUNNING, RunState.QUEUED)).isFalse();
            assertThat(RunStateMachine.canTransition(RunState.ANALYZING, RunState.RUNNING)).isFalse();
            assertThat(RunStateMachine.canTransition(RunState.PROVISIONING, RunState.VALIDATING))
                    .isFalse();
        }

        /**
         * Re-entering a state would append an event claiming a change that did not happen, which
         * corrupts the history a run is reconstructed from. Callers that must tolerate repeats ask
         * {@link RunStateMachine#isIdempotentRepeat} instead.
         */
        @ParameterizedTest
        @EnumSource(RunState.class)
        @DisplayName("a state never transitions to itself")
        void noSelfTransitions(RunState state) {
            assertThat(RunStateMachine.canTransition(state, state)).isFalse();
            assertThat(RunStateMachine.isIdempotentRepeat(state, state)).isTrue();
        }

        @Test
        @DisplayName("null states are rejected rather than treated as a wildcard")
        void nullsAreRejected() {
            assertThat(RunStateMachine.canTransition(null, RunState.QUEUED)).isFalse();
            assertThat(RunStateMachine.canTransition(RunState.QUEUED, null)).isFalse();
        }
    }

    @Nested
    @DisplayName("capacity accounting")
    class Capacity {

        /**
         * Analysis holds a slot deliberately: the run still owns its artifacts and shard records,
         * and releasing early would let an eleventh material run start while the tenth finishes.
         */
        @Test
        @DisplayName("provisioning, running and analyzing hold a concurrency slot")
        void activeStatesHoldCapacity() {
            Set<RunState> holding = Set.of(RunState.PROVISIONING, RunState.RUNNING, RunState.ANALYZING);

            for (RunState state : RunState.values()) {
                assertThat(state.holdsCapacity())
                        .as("%s capacity accounting", state)
                        .isEqualTo(holding.contains(state));
            }
        }

        @Test
        @DisplayName("only provisioning and running may have a runner behind them")
        void runnerBearingStates() {
            assertThat(RunState.PROVISIONING.mayHaveRunner()).isTrue();
            assertThat(RunState.RUNNING.mayHaveRunner()).isTrue();
            assertThat(RunState.QUEUED.mayHaveRunner()).isFalse();
            assertThat(RunState.ANALYZING.mayHaveRunner()).isFalse();
        }
    }

    @Test
    @DisplayName("DRAFT is a definition concept and is not a run state")
    void draftIsNotARunState() {
        // Documented in RunState's javadoc: a draft has no run, no plan and no capacity claim.
        assertThat(RunState.values())
                .extracting(Enum::name)
                .doesNotContain("DRAFT");
    }

    @Test
    @DisplayName("a permitted transition validates without raising")
    void permittedTransitionPasses() {
        assertThatCode(() ->
                RunStateMachine.requireTransition(RUN, RunState.QUEUED, RunState.PROVISIONING))
                .doesNotThrowAnyException();
    }
}
