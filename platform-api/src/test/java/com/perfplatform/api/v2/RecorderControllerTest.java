package com.perfplatform.api.v2;

import com.perfplatform.api.v2.error.V2ProblemHandler;
import com.perfplatform.api.v2.security.CallerContextProvider;
import com.perfplatform.domain.identity.RecorderSessionId;
import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.recorder.RecorderAuditEvent;
import com.perfplatform.domain.recorder.RecorderAuditEventType;
import com.perfplatform.domain.recorder.RecorderCapture;
import com.perfplatform.domain.recorder.RecorderBlockedDestination;
import com.perfplatform.domain.recorder.RecorderDecision;
import com.perfplatform.domain.recorder.RecorderInputEvent;
import com.perfplatform.domain.recorder.RecorderInputKind;
import com.perfplatform.domain.recorder.RecorderRequestKind;
import com.perfplatform.domain.recorder.RecorderSanitizedCapture;
import com.perfplatform.domain.recorder.RecorderScreencastFrame;
import com.perfplatform.domain.importer.SourceImporter;
import com.perfplatform.domain.recorder.RecorderSessionState;
import com.perfplatform.domain.recorder.RecorderViewport;
import com.perfplatform.domain.security.CallerContext;
import com.perfplatform.domain.security.Scope;
import com.perfplatform.domain.spec.PerformanceTestSpecification.ScenarioSpec;
import com.perfplatform.domain.validation.ValidationFinding;
import com.perfplatform.domain.validation.ValidationResult;
import com.perfplatform.orchestration.recorder.LiveRecorderSession;
import com.perfplatform.orchestration.recorder.ManageRecordingsUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RecorderControllerTest {

    private static final Instant NOW = Instant.parse("2026-08-05T10:00:00Z");
    private static final TeamId TEAM = TeamId.generate();
    private static final RecorderSessionId SESSION = RecorderSessionId.generate();

    private final ManageRecordingsUseCase recordings = mock(ManageRecordingsUseCase.class);
    private final CallerContextProvider callers = mock(CallerContextProvider.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new RecorderController(recordings, callers))
                .setControllerAdvice(new V2ProblemHandler())
                .build();

        when(callers.current()).thenReturn(new CallerContext("alice@example.com", Set.of(TEAM),
                Set.of(Scope.RECORDINGS_CREATE, Scope.RECORDINGS_READ), false));
    }

    @Test
    @DisplayName("starting a recorder session returns a clickable-safe pending session")
    void startReturnsPendingSession() throws Exception {
        when(recordings.start(any(), any())).thenReturn(session(
                RecorderSessionState.PENDING_BROWSER_BRIDGE, null));

        mockMvc.perform(post("/api/v2/recordings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "team": "advice",
                                  "application": "proposal",
                                  "environment": "performance"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v2/recordings/" + SESSION))
                .andExpect(jsonPath("$.id").value(SESSION.toString()))
                .andExpect(jsonPath("$.target").value("https://proposal.perf.internal"))
                .andExpect(jsonPath("$.status").value("PENDING_BROWSER_BRIDGE"))
                .andExpect(jsonPath("$.browserUrl").isEmpty())
                .andExpect(jsonPath("$.events[0].type").value("SESSION_REQUESTED"))
                .andExpect(jsonPath("$.bridge").value("none (no browser configured)"));
    }

    @Test
    @DisplayName("sanitizing a captured exchange returns only sanitized material and findings")
    void sanitizeReturnsSafeMaterial() throws Exception {
        when(recordings.sanitize(eq(SESSION), any(RecorderCapture.class), any()))
                .thenReturn(new RecorderSanitizedCapture(
                        "POST",
                        "https://proposal.perf.internal/orders",
                        Map.of("Content-Type", "application/json"),
                        "application/json",
                        null,
                        Map.of(),
                        "application/json",
                        "{\"ok\":true}",
                        List.of(ValidationFinding.warning("recorder.capture.body.credential",
                                "/request/body", "Body removed.", "Use a secret reference."))));

        mockMvc.perform(post("/api/v2/recordings/{sessionId}/sanitize", SESSION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "method": "POST",
                                  "url": "https://proposal.perf.internal/orders",
                                  "requestBody": "{\\"password\\":\\"lower-env\\"}"
                                }
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestBody").isEmpty())
                .andExpect(jsonPath("$.responseBody").value("{\"ok\":true}"))
                .andExpect(jsonPath("$.findings[0].code")
                        .value("recorder.capture.body.credential"));
    }

    @Test
    @DisplayName("recorder events are exposed without raw request material")
    void eventsReturnAuditRows() throws Exception {
        when(recordings.events(eq(SESSION), eq(0L), eq(100), any()))
                .thenReturn(List.of(RecorderAuditEvent.of(SESSION, TEAM,
                        RecorderAuditEventType.CAPTURE_SANITIZED, "alice@example.com",
                        Map.of("findingCount", "2"), NOW)));

        mockMvc.perform(get("/api/v2/recordings/{sessionId}/events", SESSION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("CAPTURE_SANITIZED"))
                .andExpect(jsonPath("$[0].detail.findingCount").value("2"));
    }

    // ── The bridge ───────────────────────────────────────────────────────

    @Test
    @DisplayName("a live session reports the browser URL and its recorded viewport")
    void liveSessionIsDescribed() throws Exception {
        when(recordings.describe(eq(SESSION), any()))
                .thenReturn(session(RecorderSessionState.LIVE, "https://proposal.perf.internal"));

        mockMvc.perform(get("/api/v2/recordings/{sessionId}", SESSION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LIVE"))
                .andExpect(jsonPath("$.browserUrl").value("https://proposal.perf.internal"))
                .andExpect(jsonPath("$.viewportWidth").value(1280))
                .andExpect(jsonPath("$.viewportHeight").value(800));
    }

    @Test
    @DisplayName("a poll returns the newest frame, blocked destinations and captures")
    void livePollReturnsSurface() throws Exception {
        when(recordings.live(eq(SESSION), anyLong(), anyLong(), anyLong(), anyInt(), any(), any()))
                .thenReturn(new ManageRecordingsUseCase.LiveUpdate(
                        RecorderSessionState.LIVE,
                        RecorderViewport.DEFAULT,
                        Optional.of(new RecorderScreencastFrame(7, "aGVsbG8=",
                                RecorderViewport.DEFAULT, NOW)),
                        List.of(RecorderBlockedDestination.from(3,
                                "https://ads.example/pixel?id=abc", RecorderRequestKind.SUBRESOURCE,
                                RecorderDecision.block("recorder.destination.outsideAllowlist",
                                        "Not allowed."), NOW)),
                        List.of(new LiveRecorderSession.CapturedExchange(4,
                                new RecorderSanitizedCapture("POST",
                                        "https://proposal.perf.internal/login?token=grant",
                                        Map.of(), "application/json", null, Map.of(),
                                        "application/json", null, List.of()), NOW)),
                        null, null));

        mockMvc.perform(get("/api/v2/recordings/{sessionId}/live", SESSION)
                        .param("afterFrame", "6"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LIVE"))
                .andExpect(jsonPath("$.frame.sequence").value(7))
                .andExpect(jsonPath("$.frame.imageBase64").value("aGVsbG8="))
                .andExpect(jsonPath("$.blocked[0].code")
                        .value("recorder.destination.outsideAllowlist"))
                .andExpect(jsonPath("$.captures[0].method").value("POST"));
    }

    @Test
    @DisplayName("query strings are stripped from the live view, which is where grants travel")
    void liveViewDropsQueryStrings() throws Exception {
        when(recordings.live(eq(SESSION), anyLong(), anyLong(), anyLong(), anyInt(), any(), any()))
                .thenReturn(new ManageRecordingsUseCase.LiveUpdate(
                        RecorderSessionState.LIVE,
                        RecorderViewport.DEFAULT,
                        Optional.empty(),
                        List.of(RecorderBlockedDestination.from(1,
                                "https://sso.example/callback?code=secret-grant",
                                RecorderRequestKind.TOP_LEVEL_NAVIGATION,
                                RecorderDecision.block("recorder.destination.outsideAllowlist",
                                        "Not allowed."), NOW)),
                        List.of(new LiveRecorderSession.CapturedExchange(1,
                                new RecorderSanitizedCapture("GET",
                                        "https://proposal.perf.internal/orders?apiKey=secret-key",
                                        Map.of(), null, null, Map.of(), null, null, List.of()), NOW)),
                        null, null))
                ;

        String body = mockMvc.perform(get("/api/v2/recordings/{sessionId}/live", SESSION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocked[0].destination")
                        .value("https://sso.example/callback"))
                .andExpect(jsonPath("$.captures[0].url")
                        .value("https://proposal.perf.internal/orders"))
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(body)
                .doesNotContain("secret-grant")
                .doesNotContain("secret-key");
    }

    @Test
    @DisplayName("brokered input is accepted and answered with no content")
    void inputIsAccepted() throws Exception {
        mockMvc.perform(post("/api/v2/recordings/{sessionId}/input", SESSION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"kind": "mouse-pressed", "x": 120, "y": 240,
                                 "button": "left", "clickCount": 1}
                                """))
                .andExpect(status().isNoContent());

        org.mockito.ArgumentCaptor<RecorderInputEvent> captured =
                org.mockito.ArgumentCaptor.forClass(RecorderInputEvent.class);
        verify(recordings).input(eq(SESSION), captured.capture(), any());
        org.assertj.core.api.Assertions.assertThat(captured.getValue().kind())
                .isEqualTo(RecorderInputKind.MOUSE_PRESSED);
    }

    @Test
    @DisplayName("an input kind that is not in the vocabulary is refused, not forwarded")
    void unknownInputKindIsRefused() throws Exception {
        mockMvc.perform(post("/api/v2/recordings/{sessionId}/input", SESSION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\": \"Runtime.evaluate\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("input a session refuses is a conflict, so a client knows it can retry")
    void refusedInputIsAConflict() throws Exception {
        doThrow(new ManageRecordingsUseCase.RecorderInputRefusedException(
                "Too many recorder input events; slow down."))
                .when(recordings).input(eq(SESSION), any(), any());

        mockMvc.perform(post("/api/v2/recordings/{sessionId}/input", SESSION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\": \"reload\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("recording.input.refused"));
    }

    @Test
    @DisplayName("a target the address floor refuses is 422 with its own code")
    void unsafeTargetIsUnprocessable() throws Exception {
        when(recordings.start(any(), any())).thenThrow(
                new ManageRecordingsUseCase.UnsafeRecorderTargetException("recorder.target.refused",
                        "Loopback addresses are not permitted from a recording browser."));

        mockMvc.perform(post("/api/v2/recordings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"team": "advice", "application": "proposal",
                                 "environment": "performance"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("recorder.target.refused"));
    }

    @Test
    @DisplayName("stopping a session returns its terminal state")
    void stopReturnsTerminalSession() throws Exception {
        when(recordings.stop(eq(SESSION), any()))
                .thenReturn(session(RecorderSessionState.CANCELLED, null));

        mockMvc.perform(post("/api/v2/recordings/{sessionId}/stop", SESSION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    @DisplayName("a recording is returned as a draft in the same shape a HAR import returns")
    void draftLooksLikeAnImport() throws Exception {
        when(recordings.draft(eq(SESSION), any())).thenReturn(SourceImporter.ImportResult.of(
                List.of(new ScenarioSpec("checkout", 60, null, "POST", "/orders",
                        Map.of(), Map.of(), null, List.of())),
                ValidationResult.of(ValidationFinding.warning("import.har.credentials.removed",
                        "/request/headers", "An Authorization header was removed.",
                        "Use spec.authentication with a secret reference."))));

        mockMvc.perform(get("/api/v2/recordings/{sessionId}/draft", SESSION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceType").value("har"))
                .andExpect(jsonPath("$.scenarios[0].name").value("checkout"))
                .andExpect(jsonPath("$.scenarios[0].weight").value(60))
                .andExpect(jsonPath("$.findings[0].code").value("import.har.credentials.removed"));
    }

    @Test
    @DisplayName("a recording that captured nothing returns findings, not an empty success")
    void emptyDraftExplainsItself() throws Exception {
        when(recordings.draft(eq(SESSION), any())).thenReturn(SourceImporter.ImportResult.nothing(
                ValidationResult.of(ValidationFinding.warning("recorder.draft.empty", "/",
                        "This recording captured no requests.", "Interact with the application."))));

        mockMvc.perform(get("/api/v2/recordings/{sessionId}/draft", SESSION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scenarios").isEmpty())
                .andExpect(jsonPath("$.findings[0].code").value("recorder.draft.empty"));
    }

    private static ManageRecordingsUseCase.RecordingSession session(RecorderSessionState state,
            String browserUrl) {
        return new ManageRecordingsUseCase.RecordingSession(
                SESSION, TEAM, "proposal", "performance", "https://proposal.perf.internal",
                state, browserUrl, RecorderViewport.DEFAULT, "none (no browser configured)",
                null, null, 0, 0,
                List.of(RecorderAuditEvent.of(SESSION, TEAM,
                        RecorderAuditEventType.SESSION_REQUESTED, "alice@example.com",
                        Map.of("status", state.name()), NOW)));
    }
}
