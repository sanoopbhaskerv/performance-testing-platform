package com.perfplatform.characterization;

import com.perfplatform.engine.TestRunService;
import com.perfplatform.storage.TestRun;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Characterization tests for the V1 HTTP surface and its authorization behavior.
 *
 * <p>Several tests here assert behavior that CLAUDE.md §5 and §20 require replacing. They are
 * written as passing assertions against today's behavior so the build stays green while the
 * gap stays visible and tracked; each is marked KNOWN SECURITY GAP and names the increment
 * that must invert it. See {@code docs/migration/v1-to-v2.md} for the consolidated register.
 *
 * <p>{@link TestRunService} is mocked so that triggering a run exercises the HTTP and security
 * layers without starting a JMeter subprocess.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "platform.config-dir=src/test/resources/test-teams",
        // Schema comes from Flyway as of Increment 1B, matching production configuration.
        "spring.jpa.hibernate.ddl-auto=none"
})
class ApiAuthorizationCharacterizationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TestRunService testRunService;

    private static TestRun run(String id, String team) throws Exception {
        TestRun run = new TestRun();
        Field field = TestRun.class.getDeclaredField("id");
        field.setAccessible(true);
        field.set(run, id);
        run.setTeam(team);
        run.setTestType(TestRun.TestType.LOAD);
        run.setStatus(TestRun.Status.PENDING);
        return run;
    }

    // ── Authentication ───────────────────────────────────────────────────

    /**
     * V1 returns 403 rather than 401 for an unauthenticated call: no
     * {@code AuthenticationEntryPoint} is configured, so Spring Security's default
     * access-denied handling applies to the anonymous principal. A client cannot distinguish
     * "not authenticated" from "not permitted". Increment 4 should return 401 with an RFC 7807
     * body (CLAUDE.md §19).
     */
    @Test
    @DisplayName("an API call without an API key is rejected (as 403, not 401)")
    void rejectsMissingApiKey() throws Exception {
        mockMvc.perform(get("/api/v1/config/teams"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an API call with an unrecognized API key is rejected (as 403, not 401)")
    void rejectsUnknownApiKey() throws Exception {
        mockMvc.perform(get("/api/v1/config/teams").header("X-API-Key", "not-a-real-key"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a valid team API key authenticates")
    void acceptsValidApiKey() throws Exception {
        mockMvc.perform(get("/api/v1/config/teams").header("X-API-Key", "test-key-alpha"))
                .andExpect(status().isOk());
    }

    // ── Trigger and status contract ──────────────────────────────────────

    @Test
    @DisplayName("triggering a run returns 202 Accepted with the created run")
    void triggerReturnsAccepted() throws Exception {
        when(testRunService.createAndRun(any(), any())).thenReturn(run("run-1", "alpha"));

        mockMvc.perform(post("/api/v1/tests/run")
                .header("X-API-Key", "test-key-alpha")
                .contentType("application/json")
                .content("{\"team\":\"alpha\",\"type\":\"load\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value("run-1"))
                .andExpect(jsonPath("$.team").value("alpha"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    /**
     * KNOWN GAP (CLAUDE.md §19): the trigger accepts no idempotency key, so a retried request
     * starts a second material load test. Increment 1C makes the trigger idempotent.
     */
    @Test
    @DisplayName("KNOWN GAP: the V1 trigger accepts no idempotency key")
    void triggerHasNoIdempotencyKey() throws Exception {
        when(testRunService.createAndRun(any(), any())).thenReturn(run("run-1", "alpha"));

        // The same request body submitted twice is accepted twice; nothing correlates them.
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/v1/tests/run")
                    .header("X-API-Key", "test-key-alpha")
                    .header("Idempotency-Key", "the-same-key")
                    .contentType("application/json")
                    .content("{\"team\":\"alpha\",\"type\":\"load\"}"))
                    .andExpect(status().isAccepted());
        }
    }

    /**
     * KNOWN GAP (CLAUDE.md §8, §19): the JPA entity is serialized directly as the REST
     * response, so the persistence schema is the public API contract and internal fields such
     * as the pod-local report path are exposed. Increment 1B/1F introduce response DTOs.
     */
    @Test
    @DisplayName("KNOWN GAP: the JPA entity is returned directly as the REST response")
    void exposesJpaEntityAsResponse() throws Exception {
        TestRun run = run("run-1", "alpha");
        run.setReportPath("/tmp/perf-platform/run-1/perf-report.html");
        when(testRunService.getById("run-1")).thenReturn(Optional.of(run));

        mockMvc.perform(get("/api/v1/tests/run-1").header("X-API-Key", "test-key-alpha"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportPath").value("/tmp/perf-platform/run-1/perf-report.html"));
    }

    // ── Authorization gaps ───────────────────────────────────────────────

    /**
     * KNOWN SECURITY GAP (CLAUDE.md §5 "Authentication that treats any valid key as authorized
     * for every team", §20). {@code ApiKeyAuthFilter} accepts any key present in any team's
     * config and never binds the authenticated principal to a team, so team beta's key reads
     * team alpha's run history. Increment 4 must invert this assertion.
     */
    @Test
    @DisplayName("KNOWN SECURITY GAP: one team's API key reads another team's run history")
    void anyValidKeyReadsAnyTeamHistory() throws Exception {
        when(testRunService.getHistory("alpha")).thenReturn(List.of(run("run-1", "alpha")));

        mockMvc.perform(get("/api/v1/tests/history")
                .param("team", "alpha")
                .header("X-API-Key", "test-key-beta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].team").value("alpha"));
    }

    /**
     * KNOWN SECURITY GAP (CLAUDE.md §5, §20): the same key confusion lets one team trigger
     * material load on another team's targets.
     */
    @Test
    @DisplayName("KNOWN SECURITY GAP: one team's API key triggers a run for another team")
    void anyValidKeyTriggersAnyTeamRun() throws Exception {
        when(testRunService.createAndRun(any(), any())).thenReturn(run("run-1", "alpha"));

        mockMvc.perform(post("/api/v1/tests/run")
                .header("X-API-Key", "test-key-beta")
                .contentType("application/json")
                .content("{\"team\":\"alpha\",\"type\":\"load\"}"))
                .andExpect(status().isAccepted());
    }

    /**
     * KNOWN SECURITY GAP (CLAUDE.md §5 "Public report endpoints", §17): the report endpoint is
     * explicitly permitted without authentication in {@code SecurityConfig}, so test evidence
     * is readable by anyone who can reach the service. Increment 1E replaces this with
     * authorized, time-limited download grants.
     */
    @Test
    @DisplayName("KNOWN SECURITY GAP: the run report endpoint is reachable with no API key")
    void reportEndpointIsPublic() throws Exception {
        when(testRunService.getById("run-1")).thenReturn(Optional.of(run("run-1", "alpha")));

        // No report path is set, so the controller 404s — but crucially it is not a 401,
        // proving the endpoint is not behind authentication.
        mockMvc.perform(get("/api/v1/tests/run-1/report"))
                .andExpect(status().isNotFound());
    }

    /**
     * KNOWN SECURITY GAP (CLAUDE.md §5, §17): the entire working directory is served as static
     * assets under /runs/** with no authentication, exposing raw JTL results, JMeter logs and
     * heap dump output across teams.
     */
    @Test
    @DisplayName("KNOWN SECURITY GAP: the /runs/** artifact tree is served without a key")
    void artifactTreeIsPublic() throws Exception {
        mockMvc.perform(get("/runs/some-run/results.csv"))
                .andExpect(status().isNotFound());
    }

    /**
     * The security property pinned here is reachability without a key. The HTTP status itself
     * is deliberately not asserted: V1 aggregates every health indicator, including the
     * optional mail sender, so health reports DOWN/503 whenever the SMTP host is unreachable —
     * which is the default in local development and CI. CLAUDE.md §26 requires readiness to
     * distinguish essential dependencies from optional enrichment providers; that gap is
     * recorded in docs/migration/baseline-report.md rather than pinned as an
     * environment-dependent assertion.
     */
    @Test
    @DisplayName("actuator health is reachable without an API key")
    void actuatorHealthIsPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(result -> {
                    int actual = result.getResponse().getStatus();
                    org.assertj.core.api.Assertions.assertThat(actual)
                            .as("health must not be behind authentication")
                            .isNotIn(401, 403);
                })
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"status\"")));
    }
}
