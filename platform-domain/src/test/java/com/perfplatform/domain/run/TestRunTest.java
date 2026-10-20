package com.perfplatform.domain.run;

import com.perfplatform.domain.identity.ExecutionPlanId;
import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.identity.TestDefinitionVersionId;
import com.perfplatform.domain.identity.TestRunId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The run aggregate: transitions, timeline stamping, cancellation and leases. */
class TestRunTest {

    private static final Instant T0 = Instant.parse("2026-07-29T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-07-29T10:05:00Z");
    private static final Instant T2 = Instant.parse("2026-07-29T10:10:00Z");

    private static final TeamId TEAM = TeamId.generate();
    private static final TestDefinitionVersionId VERSION = TestDefinitionVersionId.generate();

    private static RunTransition newRun() {
        return TestRun.request(TestRunId.generate(), TEAM, VERSION, "performance",
                new TestRun.TriggerContext("nightly-baseline", "sha256:abc", "alice@example.com"),
                T0);
    }

    @Nested
    @DisplayName("creation")
    class Creation {

        @Test
        @DisplayName("a new run starts validating, not queued")
        void startsValidating() {
            // Being queued is a claim on capacity, and nothing holds capacity before the platform
            // has decided the request is acceptable.
            assertThat(newRun().run().state()).isEqualTo(RunState.VALIDATING);
        }

        @Test
        @DisplayName("creation produces a REQUESTED event attributed to the caller")
        void producesRequestedEvent() {
            RunTransition created = newRun();

            assertThat(created.event().type()).isEqualTo(RunEventType.REQUESTED);
            assertThat(created.event().actor()).isEqualTo("alice@example.com");
            assertThat(created.event().detail()).containsEntry("environment", "performance");
            assertThat(created.event().occurredAt()).isEqualTo(T0);
        }

        @Test
        @DisplayName("an event cannot be paired with a different run")
        void eventMustMatchRun() {
            RunTransition created = newRun();
            RunEvent foreign = RunEvent.of(TestRunId.generate(), RunEventType.REQUESTED,
                    "alice@example.com", null, T0);

            assertThatThrownBy(() -> new RunTransition(created.run(), foreign))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("belongs to run");
        }

        @Test
        @DisplayName("a trigger without an idempotency key is rejected")
        void requiresIdempotencyKey() {
            assertThatThrownBy(() -> new TestRun.TriggerContext(" ", "sha256:abc", "alice"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("idempotencyKey");
        }
    }

    @Nested
    @DisplayName("transitions")
    class Transitions {

        @Test
        @DisplayName("every transition yields an event describing both states")
        void transitionsCarryEvents() {
            TestRun run = newRun().run();
            RunTransition queued = run.transitionTo(RunState.QUEUED, "alice@example.com", "Validated", T1);

            assertThat(queued.run().state()).isEqualTo(RunState.QUEUED);
            assertThat(queued.event().isStateChange()).isTrue();
            assertThat(queued.event().previousState()).contains(RunState.VALIDATING);
            assertThat(queued.event().newState()).contains(RunState.QUEUED);
            assertThat(queued.event().detail()).containsEntry("reason", "Validated");
        }

        @Test
        @DisplayName("an invalid transition raises rather than being applied")
        void invalidTransitionRaises() {
            TestRun run = newRun().run();

            assertThatThrownBy(() -> run.transitionTo(RunState.RUNNING, "alice", null, T1))
                    .isInstanceOf(InvalidRunTransitionException.class);
        }

        @Test
        @DisplayName("queued, started and completed timestamps are stamped as they are reached")
        void timelineIsStamped() {
            TestRun queued = newRun().run()
                    .transitionTo(RunState.QUEUED, "alice", null, T1).run();
            assertThat(queued.timeline().queuedAt()).contains(T1);
            assertThat(queued.timeline().startedAt()).isEmpty();

            TestRun running = queued
                    .transitionTo(RunState.PROVISIONING, "system", null, T1).run()
                    .transitionTo(RunState.RUNNING, "system", null, T2).run();
            assertThat(running.timeline().startedAt()).contains(T2);
            assertThat(running.timeline().completedAt()).isEmpty();

            TestRun completed = running
                    .transitionTo(RunState.ANALYZING, "system", null, T2).run()
                    .transitionTo(RunState.COMPLETED, "system", null, T2).run();
            assertThat(completed.timeline().completedAt()).contains(T2);
            // Earlier stamps are never overwritten: durations computed from them would silently
            // shorten, and the corruption would look like an improvement.
            assertThat(completed.timeline().queuedAt()).contains(T1);
            assertThat(completed.timeline().startedAt()).contains(T2);
        }

        @Test
        @DisplayName("a failure carries a stable code and a sanitized reason")
        void failureCarriesCode() {
            TestRun provisioning = newRun().run()
                    .transitionTo(RunState.QUEUED, "alice", null, T1).run()
                    .transitionTo(RunState.PROVISIONING, "system", null, T1).run();

            RunTransition failed = provisioning.failed(RunFailureCode.PROVISIONING_FAILED,
                    "The runner could not be created", RunEvent.SYSTEM_ACTOR, T2);

            assertThat(failed.run().state()).isEqualTo(RunState.FAILED);
            assertThat(failed.run().failure()).hasValueSatisfying(failure -> {
                assertThat(failure.code()).isEqualTo(RunFailureCode.PROVISIONING_FAILED);
                assertThat(failure.sanitizedReason()).isEqualTo("The runner could not be created");
            });
            assertThat(failed.event().detail())
                    .containsEntry("failureCode", "PROVISIONING_FAILED");
        }
    }

    @Nested
    @DisplayName("cancellation")
    class Cancellation {

        /**
         * The request is recorded before the run stops, so a control-plane crash in between still
         * results in the run being stopped rather than resumed.
         */
        @Test
        @DisplayName("requesting cancellation records the intent without changing state")
        void requestDoesNotChangeState() {
            TestRun running = newRun().run()
                    .transitionTo(RunState.QUEUED, "alice", null, T1).run()
                    .transitionTo(RunState.PROVISIONING, "system", null, T1).run()
                    .transitionTo(RunState.RUNNING, "system", null, T1).run();

            RunTransition requested = running.cancellationRequested("bob@example.com", "Wrong target", T2);

            assertThat(requested.run().state()).isEqualTo(RunState.RUNNING);
            assertThat(requested.run().isCancellationRequested()).isTrue();
            assertThat(requested.event().type()).isEqualTo(RunEventType.CANCELLATION_REQUESTED);
            assertThat(requested.run().cancellation()).hasValueSatisfying(cancellation -> {
                assertThat(cancellation.requestedBy()).isEqualTo("bob@example.com");
                assertThat(cancellation.reason()).isEqualTo("Wrong target");
            });
        }

        @Test
        @DisplayName("a cancellation with no reason records a placeholder rather than null")
        void reasonDefaults() {
            TestRun run = newRun().run();
            assertThat(run.cancellationRequested("bob", null, T1).run().cancellation())
                    .hasValueSatisfying(cancellation ->
                            assertThat(cancellation.reason()).isEqualTo("No reason given"));
        }
    }

    @Nested
    @DisplayName("leases")
    class Leases {

        @Test
        @DisplayName("claiming records the owner and expiry and emits a CLAIMED event")
        void claimRecordsOwner() {
            RunTransition claimed = newRun().run().claimedBy("reconciler-1", T2, T1);

            assertThat(claimed.run().claim()).hasValueSatisfying(claim -> {
                assertThat(claim.owner()).isEqualTo("reconciler-1");
                assertThat(claim.expiresAt()).isEqualTo(T2);
            });
            assertThat(claimed.event().type()).isEqualTo(RunEventType.CLAIMED);
            assertThat(claimed.event().detail()).containsEntry("owner", "reconciler-1");
        }

        @Test
        @DisplayName("an unclaimed run, and one whose lease has lapsed, are both claimable")
        void claimability() {
            TestRun unclaimed = newRun().run();
            assertThat(unclaimed.isClaimable(T1)).isTrue();

            TestRun claimed = unclaimed.claimedBy("reconciler-1", T2, T1).run();
            assertThat(claimed.isClaimable(T1)).isFalse();
            // A reconciler that died must not own the run forever, or nothing would finish it.
            assertThat(claimed.isClaimable(T2)).isTrue();
        }

        @Test
        @DisplayName("reaching a terminal state releases the lease")
        void terminalReleasesClaim() {
            TestRun claimed = newRun().run().claimedBy("reconciler-1", T2, T1).run();

            assertThat(claimed.transitionTo(RunState.CANCELLED, "bob", null, T1).run().claim())
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("execution plan")
    class Plan {

        @Test
        @DisplayName("a plan can be attached once")
        void attachesPlan() {
            ExecutionPlanId planId = ExecutionPlanId.generate();
            TestRun withPlan = newRun().run().withExecutionPlan(planId);

            assertThat(withPlan.executionPlanId()).contains(planId);
            // Re-attaching the same plan is a no-op, so a retried provisioning pass is safe.
            assertThat(withPlan.withExecutionPlan(planId).executionPlanId()).contains(planId);
        }

        @Test
        @DisplayName("a run cannot be repointed at a different plan")
        void cannotRepoint() {
            TestRun withPlan = newRun().run().withExecutionPlan(ExecutionPlanId.generate());

            assertThatThrownBy(() -> withPlan.withExecutionPlan(ExecutionPlanId.generate()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("exactly one plan");
        }
    }
}
