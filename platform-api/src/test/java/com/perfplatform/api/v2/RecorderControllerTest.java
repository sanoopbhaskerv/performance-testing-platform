package com.perfplatform.api.v2;

import com.perfplatform.api.v2.error.V2ProblemHandler;
import com.perfplatform.api.v2.security.CallerContextProvider;
import com.perfplatform.domain.identity.RecorderSessionId;
import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.recorder.RecorderAuditEvent;
import com.perfplatform.domain.recorder.RecorderAuditEventType;
import com.perfplatform.domain.recorder.RecorderCapture;
import com.perfplatform.domain.recorder.RecorderSanitizedCapture;
import com.perfplatform.domain.security.CallerContext;
import com.perfplatform.domain.security.Scope;
import com.perfplatform.domain.validation.ValidationFinding;
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
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
        when(recordings.start(any(), any())).thenReturn(new ManageRecordingsUseCase.RecordingSession(
                SESSION,
                TEAM,
                "proposal",
                "performance",
                "https://proposal.perf.internal",
                "PENDING_BROWSER_BRIDGE",
                null,
                List.of(RecorderAuditEvent.of(SESSION, TEAM,
                        RecorderAuditEventType.SESSION_REQUESTED, "alice@example.com",
                        Map.of("status", "PENDING_BROWSER_BRIDGE"), NOW))));

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
                .andExpect(jsonPath("$.events[0].type").value("SESSION_REQUESTED"));
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
}
