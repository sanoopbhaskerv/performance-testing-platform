package com.perfplatform.orchestration.run;

import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.identity.TestDefinitionVersionId;
import com.perfplatform.domain.port.DefinitionVersionCatalog.DefinitionVersion;
import com.perfplatform.domain.port.TestRunRepository;
import com.perfplatform.domain.run.RunEventType;
import com.perfplatform.domain.run.RunState;
import com.perfplatform.domain.security.CallerContext;
import com.perfplatform.domain.security.Scope;
import com.perfplatform.domain.spec.PerformanceTestSpecification;
import com.perfplatform.domain.spec.WorkloadType;
import com.perfplatform.orchestration.run.TriggerTestRunUseCase.TriggerCommand;
import com.perfplatform.orchestration.run.TriggerTestRunUseCase.TriggerResult;
import com.perfplatform.orchestration.support.InMemoryTestRunRepository;
import com.perfplatform.orchestration.support.OrchestrationFixtures;
import com.perfplatform.orchestration.support.OrchestrationFixtures.StubDefinitionVersionCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Triggering a run: idempotency, authorization and the queueing decision.
 *
 * <p>Idempotency is the property that matters most here. A duplicate trigger is not a wasted row;
 * it is a second load test against a real system, doubling the traffic and invalidating both sets
 * of results.
 */
class TriggerTestRunUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-07-29T10:00:00Z");

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final InMemoryTestRunRepository runs = new InMemoryTestRunRepository();
    private final StubDefinitionVersionCatalog versions = new StubDefinitionVersionCatalog();
    private final InMemoryRuntimeCredentialStore runtimeCredentials =
            new InMemoryRuntimeCredentialStore();

    private TriggerTestRunUseCase useCase;
    private TeamId team;
    private DefinitionVersion version;
    private CallerContext caller;

    @BeforeEach
    void setUp() {
        useCase = new TriggerTestRunUseCase(runs, versions, runtimeCredentials, clock);
        team = TeamId.generate();
        version = versions.add(team);
        caller = OrchestrationFixtures.caller(team);
    }

    private TriggerResult trigger(String idempotencyKey) {
        return useCase.trigger(new TriggerCommand(version.id(), idempotencyKey), caller);
    }

    @Nested
    @DisplayName("a new run")
    class NewRun {

        @Test
        @DisplayName("is queued, attributed to the caller, and carries the version's team")
        void queuesTheRun() {
            TriggerResult result = trigger("nightly-baseline");

            assertThat(result.replayed()).isFalse();
            assertThat(result.run().state()).isEqualTo(RunState.QUEUED);
            // The team comes from the version, never from the request: a caller states what to run,
            // not who to bill it to.
            assertThat(result.run().teamId()).isEqualTo(team);
            assertThat(result.run().definitionVersionId()).isEqualTo(version.id());
            assertThat(result.run().trigger().requestedBy()).isEqualTo("alice@example.com");
            assertThat(result.run().environment()).isEqualTo(OrchestrationFixtures.ENVIRONMENT);
        }

        @Test
        @DisplayName("records a REQUESTED event and then a state change to QUEUED")
        void recordsBothEvents() {
            trigger("nightly-baseline");

            assertThat(runs.allEvents())
                    .extracting(event -> event.type().name())
                    .containsExactly(RunEventType.REQUESTED.name(), RunEventType.STATE_CHANGED.name());

            assertThat(runs.allEvents().get(1).previousState()).contains(RunState.VALIDATING);
            assertThat(runs.allEvents().get(1).newState()).contains(RunState.QUEUED);
        }

        @Test
        @DisplayName("does not provision anything — the trigger returns before any load exists")
        void doesNotProvision() {
            assertThat(trigger("nightly-baseline").run().executionPlanId()).isEmpty();
        }
    }

    @Nested
    @DisplayName("runtime credentials")
    class RuntimeCredentials {

        @Test
        @DisplayName("run-scoped credentials are held for provisioning and not fingerprinted by value")
        void credentialsAreStoredForTheRun() {
            PerformanceTestSpecification specification = specificationWithRunCredential();
            DefinitionVersion credentialed = versions.add(team, specification, true);

            TriggerResult result = useCase.trigger(new TriggerCommand(
                    credentialed.id(), "credentialed", Map.of("api-token", "Bearer token-1")),
                    caller);

            assertThat(runtimeCredentials.find(result.run().id()))
                    .singleElement()
                    .satisfies(credential -> {
                        assertThat(credential.name()).isEqualTo("session");
                        assertThat(credential.environmentVariable()).isEqualTo("PERF_AUTH_SESSION");
                        assertThat(credential.toString()).doesNotContain("token-1");
                    });

            TriggerResult replay = useCase.trigger(new TriggerCommand(
                    credentialed.id(), "credentialed", Map.of("api-token", "Bearer token-2")),
                    caller);
            assertThat(replay.replayed()).isTrue();
            assertThat(runtimeCredentials.find(result.run().id()).get(0).value())
                    .isEqualTo("Bearer token-2");
        }

        @Test
        @DisplayName("run session acquisition does not require trigger-supplied material")
        void runSessionAcquisitionDoesNotRequireTriggerMaterial() {
            DefinitionVersion credentialed =
                    versions.add(team, specificationWithRunSession(), true);

            TriggerResult result = useCase.trigger(new TriggerCommand(
                    credentialed.id(), "broker-backed-session", Map.of()), caller);

            assertThat(result.run().state()).isEqualTo(RunState.QUEUED);
            assertThat(runtimeCredentials.find(result.run().id())).isEmpty();
        }

        @Test
        @DisplayName("a missing run-scoped credential is rejected before any run is created")
        void missingCredentialIsRejected() {
            DefinitionVersion credentialed =
                    versions.add(team, specificationWithRunCredential(), true);

            assertThatThrownBy(() -> useCase.trigger(new TriggerCommand(
                    credentialed.id(), "credentialed", Map.of()), caller))
                    .isInstanceOf(TriggerTestRunUseCase.RuntimeCredentialMissingException.class);

            assertThat(runs.allRuns()).isEmpty();
        }
    }

    @Nested
    @DisplayName("idempotency")
    class Idempotency {

        @Test
        @DisplayName("the same key returns the same run rather than starting a second test")
        void sameKeyReturnsSameRun() {
            TriggerResult first = trigger("nightly-baseline");
            TriggerResult second = trigger("nightly-baseline");

            assertThat(second.run().id()).isEqualTo(first.run().id());
            assertThat(second.replayed()).isTrue();
            assertThat(runs.allRuns()).hasSize(1);
        }

        @Test
        @DisplayName("a replay is recorded, so a pipeline's duplicate submissions are visible later")
        void replayIsRecorded() {
            trigger("nightly-baseline");
            trigger("nightly-baseline");

            assertThat(runs.allEvents())
                    .extracting(event -> event.type().name())
                    .contains(RunEventType.IDEMPOTENT_REPLAY.name());
        }

        @Test
        @DisplayName("a different key starts a genuinely new run")
        void differentKeyStartsNewRun() {
            assertThat(trigger("run-a").run().id()).isNotEqualTo(trigger("run-b").run().id());
            assertThat(runs.allRuns()).hasSize(2);
        }

        /**
         * The dangerous case. Silently returning the first run would tell a caller their new test
         * had been accepted when it had not, and they would read the old run's results as if they
         * described the new one.
         */
        @Test
        @DisplayName("reusing a key for a different version is rejected, not treated as a replay")
        void reusedKeyWithDifferentVersionConflicts() {
            trigger("nightly-baseline");
            DefinitionVersion other = versions.add(team);

            assertThatThrownBy(() -> useCase.trigger(
                    new TriggerCommand(other.id(), "nightly-baseline"), caller))
                    .isInstanceOf(TestRunRepository.IdempotencyConflictException.class)
                    .hasMessageContaining("already used");
        }

        @Test
        @DisplayName("keys are scoped per team, so two teams may use the same key")
        void keysAreScopedPerTeam() {
            trigger("nightly-baseline");

            TeamId otherTeam = TeamId.generate();
            DefinitionVersion otherVersion = versions.add(otherTeam);

            TriggerResult other = useCase.trigger(
                    new TriggerCommand(otherVersion.id(), "nightly-baseline"),
                    OrchestrationFixtures.caller(otherTeam));

            assertThat(other.replayed()).isFalse();
            assertThat(runs.allRuns()).hasSize(2);
        }

        @Test
        @DisplayName("a trigger without a key is refused")
        void keyIsRequired() {
            assertThatThrownBy(() -> new TriggerCommand(version.id(), "  "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("idempotency key");
        }
    }

    @Nested
    @DisplayName("authorization")
    class Authorization {

        @Test
        @DisplayName("a caller from another team cannot trigger this team's run")
        void otherTeamRefused() {
            CallerContext outsider = OrchestrationFixtures.caller(TeamId.generate());

            assertThatThrownBy(() ->
                    useCase.trigger(new TriggerCommand(version.id(), "attempt"), outsider))
                    .isInstanceOf(CallerContext.AuthorizationException.class);

            assertThat(runs.allRuns()).isEmpty();
        }

        @Test
        @DisplayName("a member of the team without runs:execute cannot trigger")
        void scopeIsRequired() {
            CallerContext readOnly = new CallerContext("bob@example.com", Set.of(team),
                    Set.of(Scope.RUNS_READ), false);

            assertThatThrownBy(() ->
                    useCase.trigger(new TriggerCommand(version.id(), "attempt"), readOnly))
                    .isInstanceOf(CallerContext.AuthorizationException.class);
        }

        @Test
        @DisplayName("authorization is checked before anything observable happens")
        void authorizationPrecedesEffects() {
            CallerContext outsider = OrchestrationFixtures.caller(TeamId.generate());

            assertThatThrownBy(() ->
                    useCase.trigger(new TriggerCommand(version.id(), "attempt"), outsider))
                    .isInstanceOf(CallerContext.AuthorizationException.class);

            assertThat(runs.allEvents()).isEmpty();
        }
    }

    @Nested
    @DisplayName("refusals")
    class Refusals {

        @Test
        @DisplayName("an unknown version is refused")
        void unknownVersion() {
            assertThatThrownBy(() -> useCase.trigger(
                    new TriggerCommand(TestDefinitionVersionId.generate(), "attempt"), caller))
                    .isInstanceOf(TriggerTestRunUseCase.UnknownDefinitionVersionException.class);
        }

        @Test
        @DisplayName("a version that failed validation is never queued")
        void invalidVersionRefused() {
            DefinitionVersion invalid = versions.add(team, false);

            assertThatThrownBy(() ->
                    useCase.trigger(new TriggerCommand(invalid.id(), "attempt"), caller))
                    .isInstanceOf(TriggerTestRunUseCase.VersionNotExecutableException.class);

            assertThat(runs.allRuns()).isEmpty();
        }

        @Test
        @DisplayName("browser sessions are capped separately from protocol run slots")
        void browserSessionBudgetIsEnforced() {
            useCase = new TriggerTestRunUseCase(runs, versions, runtimeCredentials, 20, clock);
            DefinitionVersion browserHeavy = versions.add(team, browserSpecification(21), true);

            assertThatThrownBy(() -> useCase.trigger(
                    new TriggerCommand(browserHeavy.id(), "too-many-browsers"), caller))
                    .isInstanceOf(TriggerTestRunUseCase.BrowserSessionBudgetExceededException.class)
                    .hasMessageContaining("21 browser sessions");

            assertThat(runs.allRuns()).isEmpty();
        }
    }

    private static PerformanceTestSpecification specificationWithRunCredential() {
        PerformanceTestSpecification base = OrchestrationFixtures.apiSpecification();
        var body = base.spec();
        var workload = body.workloads().get(0);
        var credentialed = new PerformanceTestSpecification.WorkloadSpec(
                workload.name(), workload.type(), workload.engine(), workload.sourceRef(),
                "session", workload.model(), workload.scenarios(), workload.startWhen(),
                workload.browserUsers(), workload.iterationsPerUser());
        return new PerformanceTestSpecification(base.apiVersion(), base.kind(), base.metadata(),
                new PerformanceTestSpecification.TestSpec(
                        body.environment(), body.sources(),
                        List.of(new PerformanceTestSpecification.AuthenticationSpec(
                                "session", "bearer", "Authorization", "run://api-token")),
                        body.datasets(), List.of(credentialed), body.objectives(),
                        body.observability(), body.governance()));
    }

    private static PerformanceTestSpecification specificationWithRunSession() {
        PerformanceTestSpecification base = OrchestrationFixtures.apiSpecification();
        var body = base.spec();
        var workload = body.workloads().get(0);
        var credentialed = new PerformanceTestSpecification.WorkloadSpec(
                workload.name(), workload.type(), workload.engine(), workload.sourceRef(),
                "session", workload.model(), workload.scenarios(), workload.startWhen(),
                workload.browserUsers(), workload.iterationsPerUser());
        return new PerformanceTestSpecification(base.apiVersion(), base.kind(), base.metadata(),
                new PerformanceTestSpecification.TestSpec(
                        body.environment(), body.sources(),
                        List.of(new PerformanceTestSpecification.AuthenticationSpec(
                                "session", "bearer", "Authorization", "run://session")),
                        body.datasets(), List.of(credentialed), body.objectives(),
                        body.observability(), body.governance()));
    }

    private static PerformanceTestSpecification browserSpecification(int browserUsers) {
        PerformanceTestSpecification base = OrchestrationFixtures.apiSpecification();
        var body = base.spec();
        var browser = new PerformanceTestSpecification.WorkloadSpec(
                "browser-experience",
                WorkloadType.BROWSER,
                "playwright",
                null,
                null,
                null,
                List.of(),
                null,
                browserUsers,
                1);
        return new PerformanceTestSpecification(base.apiVersion(), base.kind(), base.metadata(),
                new PerformanceTestSpecification.TestSpec(
                        body.environment(), body.sources(), body.authentication(), body.datasets(),
                        List.of(browser), body.objectives(), body.observability(), body.governance()));
    }
}
