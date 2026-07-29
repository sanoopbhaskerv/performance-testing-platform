package com.perfplatform.v2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.perfplatform.domain.identity.ApplicationId;
import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.identity.TestDefinitionId;
import com.perfplatform.domain.identity.TestDefinitionVersionId;
import com.perfplatform.domain.identity.TestRunId;
import com.perfplatform.domain.port.TestRunRepository;
import com.perfplatform.domain.run.RunEvent;
import com.perfplatform.domain.run.RunEventType;
import com.perfplatform.domain.run.RunState;
import com.perfplatform.domain.spec.SpecificationDigest;
import com.perfplatform.orchestration.run.RunReconciler;
import com.perfplatform.persistence.definition.ApplicationEntity;
import com.perfplatform.persistence.definition.ApplicationJpaRepository;
import com.perfplatform.persistence.definition.TeamEntity;
import com.perfplatform.persistence.definition.TeamJpaRepository;
import com.perfplatform.persistence.definition.TestDefinitionEntity;
import com.perfplatform.persistence.definition.TestDefinitionRegistry;
import com.perfplatform.persistence.definition.TestDefinitionVersionEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The whole V2 run lifecycle through the real application, against real PostgreSQL.
 *
 * <p>Follows the local end-to-end sequence in CLAUDE.md §25: create a definition, trigger it with an
 * idempotency key, execute through the fake provider, observe the transitions, and cancel a second
 * run. Everything is wired as production wires it — the real controllers, security chain, stores and
 * reconciler — so this catches what the unit tests structurally cannot: a bean that is not wired, a
 * transaction boundary in the wrong place, a mapping that only works in isolation.
 *
 * <p>The scheduler is disabled and the reconciler driven by hand. A background loop would make the
 * assertions racy and the failures intermittent, which is the worst kind of test to inherit.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "platform.config-dir=src/test/resources/test-teams",
        "platform.orchestration.reconciliation-enabled=false",
        "spring.jpa.hibernate.ddl-auto=none"
})
class RunLifecycleEndToEndTest {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16.2-alpine")
                    .withDatabaseName("perfplatform")
                    .withUsername("perfplatform")
                    .withPassword("perfplatform");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration/{vendor}");
    }

    /** The key from the test team fixture. Not a real credential. */
    private static final String API_KEY = "test-key-alpha";

    /** Matches {@code team:} in the alpha test fixture, which is what the key resolves to. */
    private static final String TEAM_NAME = "alpha";

    private static final Instant NOW = Instant.parse("2026-07-29T10:15:30Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestDefinitionRegistry registry;

    @Autowired
    private TeamJpaRepository teams;

    @Autowired
    private ApplicationJpaRepository applications;

    @Autowired
    private TestRunRepository runs;

    @Autowired
    private RunReconciler reconciler;

    @Autowired
    private DataSource dataSource;

    private TestDefinitionVersionId versionId;

    @BeforeEach
    void seedRegistry() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                TRUNCATE TABLE run_events, runner_shards, test_runs, execution_plans,
                    test_definition_versions, test_definitions, applications, teams
                RESTART IDENTITY CASCADE
                """);

        // The team must exist in the V2 registry, not only in YAML: the development caller-identity
        // provider resolves the API key to a team name and then to this row, and a caller whose
        // team is unknown is deliberately scoped to nothing.
        TeamId teamId = TeamId.generate();
        teams.saveAndFlush(new TeamEntity(teamId.value(), TEAM_NAME, NOW));

        ApplicationId applicationId = ApplicationId.generate();
        applications.saveAndFlush(new ApplicationEntity(applicationId.value(), teamId.value(),
                "proposal", ApplicationEntity.TargetClass.NON_PRODUCTION, NOW));

        TestDefinitionEntity definition = registry.createDefinition(TestDefinitionId.generate(),
                teamId, applicationId, "proposal-baseline-" + shortId(), "alice@example.com");

        String specification = shippedExampleAsJson();
        TestDefinitionVersionEntity version = registry.appendVersion(
                TestDefinitionId.of(definition.getId()),
                TestDefinitionVersionId.generate(),
                specification,
                SpecificationDigest.ofCanonicalForm(specification),
                TestDefinitionVersionEntity.ValidationStatus.VALID,
                "alice@example.com");

        versionId = TestDefinitionVersionId.of(version.getId());
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * The shipped API-only example, converted to JSON for storage.
     *
     * <p>Uses the real fixture rather than a hand-written stub so this test breaks if the example
     * and the parser ever diverge — the same reason the 1B validation tests read the shipped files.
     */
    private static String shippedExampleAsJson() {
        Path example = repositoryRoot()
                .resolve("specification/examples/valid/api-only.yaml");
        try {
            ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
            JsonNode root = yaml.readTree(Files.readString(example));
            // Point the document at the team this test seeded, so the fixture reads coherently.
            ((ObjectNode) root.get("metadata")).put("team", TEAM_NAME);
            return new ObjectMapper().writeValueAsString(root);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + example, e);
        }
    }

    private static Path repositoryRoot() {
        Path candidate = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (candidate != null) {
            if (Files.isDirectory(candidate.resolve("specification"))
                    && Files.isRegularFile(candidate.resolve("pom.xml"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("Could not locate the repository root");
    }

    private String triggerBody() {
        return "{\"definitionVersionId\":\"" + versionId + "\"}";
    }

    private String trigger(String idempotencyKey) throws Exception {
        return mockMvc.perform(post("/api/v2/test-runs")
                        .header("X-API-Key", API_KEY)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(triggerBody()))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
    }

    private static String runIdOf(String responseBody) throws IOException {
        return new ObjectMapper().readTree(responseBody).get("id").asText();
    }

    @Test
    @DisplayName("a run goes from trigger to COMPLETED through the real stack")
    void fullLifecycle() throws Exception {
        String runId = runIdOf(trigger("nightly-baseline"));

        mockMvc.perform(get("/api/v2/test-runs/" + runId).header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.environment").value("performance"));

        // Admitted, compiled and provisioned.
        reconciler.reconcile();
        mockMvc.perform(get("/api/v2/test-runs/" + runId).header("X-API-Key", API_KEY))
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.executionPlanId").isNotEmpty());

        reconciler.reconcile();
        reconciler.reconcile();

        mockMvc.perform(get("/api/v2/test-runs/" + runId).header("X-API-Key", API_KEY))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.completedAt").isNotEmpty())
                .andExpect(jsonPath("$.failureCode").doesNotExist());
    }

    @Test
    @DisplayName("the run's history records every transition in order")
    void historyIsComplete() throws Exception {
        String runId = runIdOf(trigger("nightly-baseline"));
        reconciler.reconcile();
        reconciler.reconcile();
        reconciler.reconcile();

        mockMvc.perform(get("/api/v2/test-runs/" + runId + "/events").header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("REQUESTED"));

        List<RunEvent> events = runs.events(TestRunId.of(runId), 0, 100);

        assertThat(events)
                .filteredOn(event -> event.type() == RunEventType.STATE_CHANGED)
                .extracting(event -> event.newState().orElseThrow())
                .containsExactly(RunState.QUEUED, RunState.PROVISIONING, RunState.RUNNING,
                        RunState.ANALYZING, RunState.COMPLETED);

        assertThat(events)
                .extracting(RunEvent::type)
                .contains(RunEventType.CLAIMED, RunEventType.SHARD_PROVISIONED);
    }

    @Test
    @DisplayName("a duplicate trigger returns the same run and is marked as a replay")
    void duplicateTriggerIsIdempotent() throws Exception {
        String firstRunId = runIdOf(trigger("nightly-baseline"));

        mockMvc.perform(post("/api/v2/test-runs")
                        .header("X-API-Key", API_KEY)
                        .header("Idempotency-Key", "nightly-baseline")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(triggerBody()))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andExpect(jsonPath("$.id").value(firstRunId));
    }

    @Test
    @DisplayName("a second run is cancelled and its runner stopped")
    void cancelSecondRun() throws Exception {
        String firstRunId = runIdOf(trigger("first-run"));
        String secondRunId = runIdOf(trigger("second-run"));

        // The team's ceiling is one, so only the first is admitted; the second waits its turn.
        reconciler.reconcile();
        mockMvc.perform(get("/api/v2/test-runs/" + secondRunId).header("X-API-Key", API_KEY))
                .andExpect(jsonPath("$.status").value("QUEUED"));

        // Nothing is executing for it yet, so cancelling stops it outright.
        mockMvc.perform(post("/api/v2/test-runs/" + secondRunId + "/cancel")
                        .header("X-API-Key", API_KEY)
                        .param("reason", "Submitted against the wrong environment"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("CANCELLED"))
                .andExpect(jsonPath("$.run.status").value("CANCELLED"));

        // Repeating it succeeds: people retry a stop button that looks like it did nothing.
        mockMvc.perform(post("/api/v2/test-runs/" + secondRunId + "/cancel")
                        .header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("ALREADY_CANCELLED"));

        // The first run is unaffected.
        reconciler.reconcile();
        reconciler.reconcile();
        mockMvc.perform(get("/api/v2/test-runs/" + firstRunId).header("X-API-Key", API_KEY))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    @DisplayName("a running run is cancelled through the reconciler")
    void cancelRunningRun() throws Exception {
        String runId = runIdOf(trigger("nightly-baseline"));
        reconciler.reconcile();

        mockMvc.perform(post("/api/v2/test-runs/" + runId + "/cancel")
                        .header("X-API-Key", API_KEY)
                        .param("reason", "Wrong target"))
                .andExpect(status().isOk())
                // The runner is still live, so the request is recorded rather than the run being
                // declared stopped before the load actually ceased.
                .andExpect(jsonPath("$.outcome").value("CANCELLATION_REQUESTED"))
                .andExpect(jsonPath("$.run.status").value("RUNNING"));

        reconciler.reconcile();

        mockMvc.perform(get("/api/v2/test-runs/" + runId).header("X-API-Key", API_KEY))
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    @DisplayName("V2 endpoints reject an unauthenticated caller")
    void v2RequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/v2/test-runs")
                        .header("Idempotency-Key", "nightly-baseline")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(triggerBody()))
                // 403 rather than 401 is V1's documented defect D7, inherited by the shared filter
                // chain and owned by Increment 4. Asserted so the gap stays visible.
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("V1 endpoints still work alongside V2")
    void v1RemainsOperational() throws Exception {
        mockMvc.perform(get("/api/v1/config/teams").header("X-API-Key", API_KEY))
                .andExpect(status().isOk());
    }
}
