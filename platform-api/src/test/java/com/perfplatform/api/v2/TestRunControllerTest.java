package com.perfplatform.api.v2;

import com.perfplatform.api.v2.error.V2ProblemHandler;
import com.perfplatform.api.v2.security.CallerContextProvider;
import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.identity.TestDefinitionVersionId;
import com.perfplatform.domain.identity.TestRunId;
import com.perfplatform.domain.port.TestRunRepository;
import com.perfplatform.domain.run.RunEvent;
import com.perfplatform.domain.run.RunEventType;
import com.perfplatform.domain.run.RunState;
import com.perfplatform.domain.run.TestRun;
import com.perfplatform.domain.security.CallerContext;
import com.perfplatform.domain.security.Scope;
import com.perfplatform.orchestration.run.CancelTestRunUseCase;
import com.perfplatform.orchestration.run.ReadTestRunUseCase;
import com.perfplatform.orchestration.run.TriggerTestRunUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The V2 run endpoints at the HTTP boundary.
 *
 * <p>Status codes and problem bodies are the part CI integrations actually key on, so they are
 * asserted as a caller sees them rather than by calling controller methods. The use cases are
 * stubbed: their behaviour is covered by their own tests, and what matters here is the mapping
 * between an outcome and a response.
 */
class TestRunControllerTest {

    private static final Instant NOW = Instant.parse("2026-07-29T10:00:00Z");
    private static final TeamId TEAM = TeamId.generate();
    private static final TestDefinitionVersionId VERSION = TestDefinitionVersionId.generate();

    private final TriggerTestRunUseCase triggerRun = mock(TriggerTestRunUseCase.class);
    private final CancelTestRunUseCase cancelRun = mock(CancelTestRunUseCase.class);
    private final ReadTestRunUseCase readRun = mock(ReadTestRunUseCase.class);
    private final CallerContextProvider callers = mock(CallerContextProvider.class);

    private MockMvc mockMvc;
    private TestRun run;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new TestRunController(triggerRun, cancelRun, readRun, callers))
                .setControllerAdvice(new V2ProblemHandler())
                .build();

        when(callers.current()).thenReturn(new CallerContext("alice@example.com", Set.of(TEAM),
                Set.of(Scope.RUNS_EXECUTE, Scope.RUNS_CANCEL, Scope.RUNS_READ), false));

        run = TestRun.request(TestRunId.generate(), TEAM, VERSION, "performance",
                new TestRun.TriggerContext("nightly", "sha256:abc", "alice@example.com"), NOW)
                .run()
                .transitionTo(RunState.QUEUED, "alice@example.com", null, NOW).run();
    }

    private String triggerBody() {
        return "{\"definitionVersionId\":\"" + VERSION + "\"}";
    }

    @Nested
    @DisplayName("POST /api/v2/test-runs")
    class Trigger {

        /**
         * 202, not 201. The run exists but nothing has executed, and reporting it as created would
         * suggest the test had started.
         */
        @Test
        @DisplayName("returns 202 with a Location header and does not wait for the test")
        void returnsAccepted() throws Exception {
            when(triggerRun.trigger(any(), any()))
                    .thenReturn(new TriggerTestRunUseCase.TriggerResult(run, false));

            mockMvc.perform(post("/api/v2/test-runs")
                            .header("Idempotency-Key", "nightly")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(triggerBody()))
                    .andExpect(status().isAccepted())
                    .andExpect(header().string("Location", "/api/v2/test-runs/" + run.id()))
                    .andExpect(header().string("Idempotent-Replay", "false"))
                    .andExpect(jsonPath("$.id").value(run.id().toString()))
                    .andExpect(jsonPath("$.status").value("QUEUED"))
                    .andExpect(jsonPath("$.teamId").value(TEAM.toString()));
        }

        @Test
        @DisplayName("marks a replayed trigger so a pipeline can tell 'started' from 'already started'")
        void marksReplay() throws Exception {
            when(triggerRun.trigger(any(), any()))
                    .thenReturn(new TriggerTestRunUseCase.TriggerResult(run, true));

            mockMvc.perform(post("/api/v2/test-runs")
                            .header("Idempotency-Key", "nightly")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(triggerBody()))
                    .andExpect(status().isAccepted())
                    .andExpect(header().string("Idempotent-Replay", "true"));
        }

        @Test
        @DisplayName("a missing idempotency key is a 400 naming the header")
        void missingKeyIsBadRequest() throws Exception {
            mockMvc.perform(post("/api/v2/test-runs")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(triggerBody()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("request.header.missing"))
                    .andExpect(jsonPath("$.detail").value(
                            org.hamcrest.Matchers.containsString("Idempotency-Key")));
        }

        @Test
        @DisplayName("a missing definitionVersionId is a 400 with field findings")
        void missingVersionIsBadRequest() throws Exception {
            mockMvc.perform(post("/api/v2/test-runs")
                            .header("Idempotency-Key", "nightly")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("request.body.invalid"))
                    .andExpect(jsonPath("$.findings[0].path").value("definitionVersionId"));
        }

        @Test
        @DisplayName("an unknown version is a 404")
        void unknownVersionIsNotFound() throws Exception {
            when(triggerRun.trigger(any(), any())).thenThrow(
                    new TriggerTestRunUseCase.UnknownDefinitionVersionException(VERSION));

            mockMvc.perform(post("/api/v2/test-runs")
                            .header("Idempotency-Key", "nightly")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(triggerBody()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("definitionVersion.notFound"));
        }

        /**
         * 422, not 400: the request was well formed and understood, and the problem is with what it
         * refers to. A caller can tell "I sent bad JSON" from "the test itself is not runnable".
         */
        @Test
        @DisplayName("a version that failed validation is a 422")
        void notExecutableIsUnprocessable() throws Exception {
            when(triggerRun.trigger(any(), any())).thenThrow(
                    new TriggerTestRunUseCase.VersionNotExecutableException(VERSION));

            mockMvc.perform(post("/api/v2/test-runs")
                            .header("Idempotency-Key", "nightly")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(triggerBody()))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("definitionVersion.notExecutable"));
        }

        @Test
        @DisplayName("a reused idempotency key is a 409 naming the run it already belongs to")
        void idempotencyConflictIsConflict() throws Exception {
            when(triggerRun.trigger(any(), any())).thenThrow(
                    new TestRunRepository.IdempotencyConflictException("nightly", run.id()));

            mockMvc.perform(post("/api/v2/test-runs")
                            .header("Idempotency-Key", "nightly")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(triggerBody()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("run.idempotencyKey.conflict"))
                    .andExpect(jsonPath("$.existingRunId").value(run.id().toString()));
        }
    }

    @Nested
    @DisplayName("GET /api/v2/test-runs/{runId}")
    class Read {

        @Test
        @DisplayName("returns the run without exposing internal mechanics")
        void returnsRun() throws Exception {
            when(readRun.get(any(), any())).thenReturn(run);

            mockMvc.perform(get("/api/v2/test-runs/" + run.id()))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.status").value("QUEUED"))
                    .andExpect(jsonPath("$.requestedBy").value("alice@example.com"))
                    // The reconciler lease and the optimistic-lock version are internal mechanics.
                    // Exposing them would make them things consumers depend on.
                    .andExpect(jsonPath("$.claim").doesNotExist())
                    .andExpect(jsonPath("$.lockVersion").doesNotExist());
        }

        @Test
        @DisplayName("a malformed run id is a 400, not a 500")
        void malformedIdIsBadRequest() throws Exception {
            mockMvc.perform(get("/api/v2/test-runs/not-a-uuid"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("request.invalid"));
        }

        /**
         * Deliberately indistinguishable from a run that does not exist. Returning 403 for someone
         * else's run and 404 for a fictional one turns the endpoint into an oracle for which run
         * identifiers are real.
         */
        @Test
        @DisplayName("another team's run is a 404, the same as one that does not exist")
        void otherTeamsRunIsNotFound() throws Exception {
            when(readRun.get(any(), any()))
                    .thenThrow(new CancelTestRunUseCase.UnknownRunException(run.id()));

            mockMvc.perform(get("/api/v2/test-runs/" + run.id()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("run.notFound"));
        }

        @Test
        @DisplayName("an unauthenticated request is a 401")
        void unauthenticatedIsUnauthorized() throws Exception {
            when(callers.current()).thenThrow(
                    new CallerContextProvider.UnauthenticatedException("no identity"));

            mockMvc.perform(get("/api/v2/test-runs/" + run.id()))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("auth.unauthenticated"));
        }

        @Test
        @DisplayName("a forbidden request says nothing about which scope or team was missing")
        void forbiddenLeaksNothing() throws Exception {
            when(readRun.get(any(), any())).thenThrow(
                    new CallerContext.AuthorizationException("alice@example.com", Scope.RUNS_READ));

            mockMvc.perform(get("/api/v2/test-runs/" + run.id()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("auth.forbidden"))
                    .andExpect(jsonPath("$.detail").value("You are not permitted to perform this action."));
        }
    }

    @Nested
    @DisplayName("GET /api/v2/test-runs/{runId}/events")
    class Events {

        @Test
        @DisplayName("returns the history with sequence numbers for resuming")
        void returnsEvents() throws Exception {
            when(readRun.events(any(), anyLong(), anyInt(), any())).thenReturn(List.of(
                    new RunEvent(1L, run.id(), RunEventType.REQUESTED, Optional.empty(),
                            Optional.empty(), "alice@example.com",
                            Map.of("environment", "performance"), NOW),
                    RunEvent.stateChanged(run.id(), RunState.VALIDATING, RunState.QUEUED,
                            "alice@example.com", Map.of(), NOW).withSequence(2L)));

            mockMvc.perform(get("/api/v2/test-runs/" + run.id() + "/events"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].sequence").value(1))
                    .andExpect(jsonPath("$[0].type").value("REQUESTED"))
                    .andExpect(jsonPath("$[0].detail.environment").value("performance"))
                    .andExpect(jsonPath("$[1].previousState").value("VALIDATING"))
                    .andExpect(jsonPath("$[1].newState").value("QUEUED"));
        }
    }

    @Nested
    @DisplayName("POST /api/v2/test-runs/{runId}/cancel")
    class Cancel {

        @Test
        @DisplayName("reports CANCELLED when nothing was executing")
        void cancelledImmediately() throws Exception {
            TestRun cancelled = run.transitionTo(RunState.CANCELLED, "alice", "Wrong target", NOW).run();
            when(cancelRun.cancel(any(), any(), any())).thenReturn(
                    new CancelTestRunUseCase.CancellationOutcome(cancelled,
                            CancelTestRunUseCase.Status.CANCELLED));

            mockMvc.perform(post("/api/v2/test-runs/" + run.id() + "/cancel")
                            .param("reason", "Wrong target"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.outcome").value("CANCELLED"))
                    .andExpect(jsonPath("$.run.status").value("CANCELLED"));
        }

        /**
         * The state alone would say RUNNING, and a caller shown only that would conclude their
         * request had been ignored and press the button again.
         */
        @Test
        @DisplayName("reports CANCELLATION_REQUESTED while a runner is still being stopped")
        void cancellationPending() throws Exception {
            when(cancelRun.cancel(any(), any(), any())).thenReturn(
                    new CancelTestRunUseCase.CancellationOutcome(run,
                            CancelTestRunUseCase.Status.CANCELLATION_REQUESTED));

            mockMvc.perform(post("/api/v2/test-runs/" + run.id() + "/cancel"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.outcome").value("CANCELLATION_REQUESTED"));
        }

        @Test
        @DisplayName("repeating a cancellation succeeds")
        void repeatIsIdempotent() throws Exception {
            when(cancelRun.cancel(any(), any(), any())).thenReturn(
                    new CancelTestRunUseCase.CancellationOutcome(run,
                            CancelTestRunUseCase.Status.ALREADY_CANCELLED));

            mockMvc.perform(post("/api/v2/test-runs/" + run.id() + "/cancel"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.outcome").value("ALREADY_CANCELLED"));
        }

        @Test
        @DisplayName("cancelling a finished run is a 409 that says what state it is in")
        void finishedRunIsConflict() throws Exception {
            when(cancelRun.cancel(any(), any(), any())).thenThrow(
                    new CancelTestRunUseCase.RunNotCancellableException(run.id(), RunState.COMPLETED,
                            "it has already finished with status COMPLETED"));

            mockMvc.perform(post("/api/v2/test-runs/" + run.id() + "/cancel"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("run.notCancellable"))
                    .andExpect(jsonPath("$.state").value("COMPLETED"));
        }
    }

    @Test
    @DisplayName("problem responses carry a correlation id and never a stack trace")
    void problemsCarryCorrelationIdWithoutInternals() throws Exception {
        when(readRun.get(any(), any())).thenThrow(new IllegalStateException(
                "jdbc:postgresql://internal-host:5432/perfplatform failed"));

        String body = mockMvc.perform(get("/api/v2/test-runs/" + run.id()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("internal.error"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        // The underlying message can name hosts, connection strings and occasionally request
        // content. It goes to the log behind the correlation id, never to the caller.
        org.assertj.core.api.Assertions.assertThat(body)
                .doesNotContain("jdbc:postgresql")
                .doesNotContain("internal-host")
                .doesNotContain("java.lang.IllegalStateException");
    }
}
