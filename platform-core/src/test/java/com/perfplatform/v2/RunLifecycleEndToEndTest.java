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
import com.perfplatform.domain.port.ArtifactStore;
import com.perfplatform.domain.port.DefinitionVersionCatalog;
import com.perfplatform.domain.port.ExecutionPlanRepository;
import com.perfplatform.domain.port.ResultCatalog;
import com.perfplatform.domain.port.RunnerShardRepository;
import com.perfplatform.domain.port.TestRunRepository;
import com.perfplatform.domain.results.NormalizedRunResult;
import com.perfplatform.domain.results.SampleSet;
import com.perfplatform.domain.run.RunEvent;
import com.perfplatform.domain.run.RunEventType;
import com.perfplatform.domain.run.RunState;
import com.perfplatform.domain.run.RunnerShard;
import com.perfplatform.domain.spec.SpecificationDigest;
import com.perfplatform.engine.spi.RunnerOutput;
import com.perfplatform.orchestration.plan.EngineRegistry;
import com.perfplatform.orchestration.run.RunReconciler;
import com.perfplatform.orchestration.run.RunWorkloadService;
import com.perfplatform.results.metrics.RunResultReducer;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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

    /**
     * A second team's key from the fixture, for cross-team authorization checks. Not a real credential.
     *
     * <p>Its team is registered too, so this caller is genuinely authenticated and genuinely a member
     * of a team — just not of the team that owns the run. A caller belonging to no team at all would
     * be refused for a different reason and would prove nothing about team isolation.
     */
    private static final String OTHER_TEAM_API_KEY = "test-key-beta";

    /** Matches {@code team:} in the alpha test fixture, which is what the key resolves to. */
    private static final String TEAM_NAME = "alpha";

    /** Matches {@code team:} in the beta test fixture. */
    private static final String OTHER_TEAM_NAME = "beta";

    /**
     * Matches a {@code targets[].name} in the alpha fixture.
     *
     * <p>That correspondence is the contract: an application resolves to the team target of the same
     * name, which is how the team synchronizer creates applications and how the target resolver finds
     * their base URL. A run whose application names no target cannot be pointed anywhere.
     */
    private static final String APPLICATION_NAME = "alpha-service";

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

    /** For re-driving analysis directly, which is what re-analysing stored artifacts does. */
    @Autowired
    private RunWorkloadService analysis;

    @Autowired
    private ExecutionPlanRepository plans;

    @Autowired
    private RunnerShardRepository shards;

    @Autowired
    private DefinitionVersionCatalog versions;

    /** For locating the artifact root, so a test can corrupt stored content the way a fault would. */
    @Autowired
    private OrchestrationProperties orchestrationProperties;

    /** For recomputing a run's measurements from its stored results and comparing. */
    @Autowired
    private ResultCatalog catalog;

    @Autowired
    private ArtifactStore artifactStore;

    @Autowired
    private EngineRegistry engines;

    /** For registering the target a run is permitted to point at. */
    @Autowired
    private com.perfplatform.domain.port.TargetRegistry targetRegistry;

    private TestDefinitionVersionId versionId;

    @BeforeEach
    void seedRegistry() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                TRUNCATE TABLE run_metrics, objective_results, artifacts, run_events, runner_shards,
                    test_runs, execution_plans, test_definition_versions, test_definitions,
                    datasets, applications, teams
                RESTART IDENTITY CASCADE
                """);

        // The team must exist in the V2 registry, not only in YAML: the development caller-identity
        // provider resolves the API key to a team name and then to this row, and a caller whose
        // team is unknown is deliberately scoped to nothing.
        TeamId teamId = TeamId.generate();
        teams.saveAndFlush(new TeamEntity(teamId.value(), TEAM_NAME, NOW));

        ApplicationId applicationId = ApplicationId.generate();
        applications.saveAndFlush(new ApplicationEntity(applicationId.value(), teamId.value(),
                APPLICATION_NAME, ApplicationEntity.TargetClass.NON_PRODUCTION, NOW));

        // Where this team's traffic is permitted to go. Registered explicitly rather than relied on
        // from the YAML fixture: the synchronizer copies those rows in at startup, and the truncate
        // above removes them along with the team they belong to. Without one, provisioning refuses
        // the run — which is the platform behaving correctly and the test failing for a reason that
        // has nothing to do with what it is checking.
        //
        // The address is never dialled. This suite runs the fake execution provider, which generates
        // no load; it is here because a run must name a permitted target before it may start.
        targetRegistry.register(teamId, applicationId, APPLICATION_NAME, "performance",
                com.perfplatform.domain.spec.TargetClass.NON_PRODUCTION,
                com.perfplatform.domain.target.TargetAddress.parse("http://localhost:18099"),
                "alice@example.com");

        // The second team owns nothing here. It exists so a cross-team request comes from a caller
        // who is properly authenticated and properly scoped — to a different team.
        teams.saveAndFlush(new TeamEntity(TeamId.generate().value(), OTHER_TEAM_NAME, NOW));

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

        uploadDataset();
    }

    /**
     * The dataset the shipped example declares, uploaded through the real endpoint.
     *
     * <p>Not seeded directly into the tables: the point of doing it over HTTP is that this test then
     * covers the whole of Increment 2h — upload, storage, the catalog row, resolution at
     * provisioning, slicing, and delivery — rather than only the half after the data exists.
     *
     * <p>The example pins {@code dataset://proposal-clients/1}, so this must be the first version
     * uploaded for that name. It is: the table is truncated above.
     */
    private void uploadDataset() throws Exception {
        mockMvc.perform(post("/api/v2/datasets")
                        .header("X-API-Key", API_KEY)
                        .param("team", TEAM_NAME)
                        .param("name", "proposal-clients")
                        .contentType("text/csv")
                        .content("""
                                clientId,accountId
                                C-1001,A-5001
                                C-1002,A-5002
                                C-1003,A-5003
                                """))
                .andExpect(status().isCreated());
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
            // Point the document at the team and application this test seeded, so the target
            // resolver can find the base URL the run should address.
            ((ObjectNode) root.get("metadata")).put("team", TEAM_NAME);
            ((ObjectNode) root.get("metadata")).put("application", APPLICATION_NAME);
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

    /**
     * The wiring, end to end.
     *
     * <p>Asserts a terminal state rather than {@code COMPLETED}. This deployment runs the fake
     * execution provider, which generates no load, so the correct outcome is a run marked synthetic
     * whose objectives are indeterminate — never a pass. This test's job is the wiring: that the real
     * controllers, security chain, stores, compiler, engine and reconciler drive a run from a trigger
     * to a terminal state through every intermediate transition. Verdict logic is
     * {@code RunAnalysisTest}'s job and is covered there for every outcome.
     */
    @Test
    @DisplayName("a run goes from trigger to a terminal state through the real stack")
    void fullLifecycle() throws Exception {
        String runId = runIdOf(trigger("nightly-baseline"));

        mockMvc.perform(get("/api/v2/test-runs/" + runId).header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.environment").value("performance"))
                .andExpect(jsonPath("$.synthetic").value(false));

        // Admitted, compiled and provisioned. The synthetic marking lands here — at the moment the
        // provider was asked to start, before any measurement exists for it to qualify.
        reconciler.reconcile();
        mockMvc.perform(get("/api/v2/test-runs/" + runId).header("X-API-Key", API_KEY))
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.executionPlanId").isNotEmpty())
                .andExpect(jsonPath("$.synthetic").value(true));

        reconciler.reconcile();
        reconciler.reconcile();

        mockMvc.perform(get("/api/v2/test-runs/" + runId).header("X-API-Key", API_KEY))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.completedAt").isNotEmpty())
                .andExpect(jsonPath("$.synthetic").value(true))
                .andExpect(jsonPath("$.failureCode").value("NO_LOAD_GENERATED"));

        // Every objective is indeterminate, with the reason. Not a breach — the system under test may
        // be perfectly healthy — and emphatically not a pass.
        mockMvc.perform(get("/api/v2/test-runs/" + runId + "/objectives")
                        .header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThan(0))))
                .andExpect(jsonPath("$[*].verdict",
                        everyItem(equalTo("INDETERMINATE"))))
                .andExpect(jsonPath("$[0].detail")
                        .value(containsString("No load was generated")));
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
                        RunState.ANALYZING, RunState.FAILED);

        assertThat(events)
                .extracting(RunEvent::type)
                .contains(RunEventType.CLAIMED, RunEventType.SHARD_PROVISIONED);

        // The provider that executed the run is part of the run's own record, not only of the log.
        assertThat(events)
                .filteredOn(event -> event.type() == RunEventType.SHARD_PROVISIONED)
                .allSatisfy(event ->
                        assertThat(event.detail().get("provider")).isEqualTo("fake"));
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

        // The first run is unaffected and finishes on its own. FAILED rather than COMPLETED because
        // this deployment generates no load; what matters here is that cancelling the second run did
        // not disturb the first.
        reconciler.reconcile();
        reconciler.reconcile();
        mockMvc.perform(get("/api/v2/test-runs/" + firstRunId).header("X-API-Key", API_KEY))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureCode").value("NO_LOAD_GENERATED"));
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

    @Test
    @DisplayName("the console can see which execution provider this deployment runs")
    void executionCapabilityIsDiscoverable() throws Exception {
        mockMvc.perform(get("/api/v2/platform/execution").header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providerId").value("fake"))
                .andExpect(jsonPath("$.generatesRealLoad").value(false))
                .andExpect(jsonPath("$.summary").value(containsString("No load is generated")));
    }

    // ── Reports ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a report before analysis is honest about having measured nothing")
    void reportBeforeAnalysisFabricatesNothing() throws Exception {
        String runId = runIdOf(trigger("nightly-baseline"));

        mockMvc.perform(get("/api/v2/test-runs/" + runId + "/report").header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.run.status").value("QUEUED"))
                // Null, not an object full of zeros. A report showing 0ms for a run that has not
                // started would describe a finished test that does not exist.
                .andExpect(jsonPath("$.measurements").doesNotExist())
                // Not admitted yet, so nothing has been compiled.
                .andExpect(jsonPath("$.execution").doesNotExist())
                .andExpect(jsonPath("$.objectives").isEmpty())
                .andExpect(jsonPath("$.summary.demonstrated").value(false))
                // The test itself is readable from the moment the run exists.
                .andExpect(jsonPath("$.test.name").isNotEmpty())
                .andExpect(jsonPath("$.test.workloads", hasSize(greaterThan(0))));
    }

    @Test
    @DisplayName("a report for a synthetic run is marked as synthetic, not only the run")
    void reportCarriesTheSyntheticMarking() throws Exception {
        String runId = runIdOf(trigger("nightly-baseline"));
        reconciler.reconcile();
        reconciler.reconcile();
        reconciler.reconcile();

        mockMvc.perform(get("/api/v2/test-runs/" + runId + "/report").header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                // At the top level, not buried inside `run`. A client rendering a number from this
                // payload must not have to know where to look for the caveat on it.
                .andExpect(jsonPath("$.synthetic").value(true))
                .andExpect(jsonPath("$.syntheticNotice")
                        .value(containsString("describe nothing")))
                .andExpect(jsonPath("$.run.synthetic").value(true))
                .andExpect(jsonPath("$.summary.passed").value(0))
                .andExpect(jsonPath("$.summary.demonstrated").value(false))
                .andExpect(jsonPath("$.objectives[*].verdict",
                        everyItem(equalTo("INDETERMINATE"))));
    }

    @Test
    @DisplayName("an analyzed run's report carries the measurements and the plan's identity")
    void reportCarriesMeasurements() throws Exception {
        String runId = runIdOf(trigger("nightly-baseline"));
        reconciler.reconcile();
        reconciler.reconcile();
        reconciler.reconcile();

        mockMvc.perform(get("/api/v2/test-runs/" + runId + "/report").header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                // Read back from run_metrics, which analysis wrote. Before this increment these
                // numbers were computed and discarded.
                .andExpect(jsonPath("$.measurements.overall.sampleCount").value(greaterThan(0)))
                .andExpect(jsonPath("$.measurements.overall.p95Millis").value(greaterThan(0)))
                .andExpect(jsonPath("$.measurements.complete").value(true))
                .andExpect(jsonPath("$.measurements.shardsReported").value(1))
                .andExpect(jsonPath("$.measurements.operations", hasSize(greaterThan(0))))
                // The plan digest answers "are these results from the test that was approved".
                .andExpect(jsonPath("$.execution.planDigest").value(containsString("sha256:")))
                .andExpect(jsonPath("$.execution.engine").value("jmeter"))
                .andExpect(jsonPath("$.execution.shardCount").value(1))
                // The workload profile, in consumer language, with weights preserved.
                .andExpect(jsonPath("$.test.workloads[0].scenarios", hasSize(greaterThan(0))))
                .andExpect(jsonPath("$.test.workloads[0].stages", hasSize(greaterThan(0))))
                .andExpect(jsonPath("$.timeline", hasSize(greaterThan(0))))
                .andExpect(jsonPath("$.artifacts", hasSize(greaterThan(0))));
    }

    @Test
    @DisplayName("re-analysing a run replaces its measurements rather than duplicating them")
    void reanalysisReplacesMeasurements() throws Exception {
        String runId = runIdOf(trigger("nightly-baseline"));
        reconciler.reconcile();
        reconciler.reconcile();
        reconciler.reconcile();

        String first = mockMvc.perform(get("/api/v2/test-runs/" + runId + "/report")
                        .header("X-API-Key", API_KEY))
                .andReturn().getResponse().getContentAsString();
        int operationsFirst = new ObjectMapper().readTree(first)
                .get("measurements").get("operations").size();

        // A terminal run is not reconciled again, so analysis is re-driven directly — which is what
        // a re-analysis of stored artifacts does, and the case the unique constraint exists for.
        analysis.analyze(
                runs.find(TestRunId.of(runId)).orElseThrow(),
                plans.findByRun(TestRunId.of(runId)),
                versions.find(versionId).orElseThrow(),
                shards.findByRun(TestRunId.of(runId)));

        mockMvc.perform(get("/api/v2/test-runs/" + runId + "/report").header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.measurements.operations", hasSize(operationsFirst)));
    }

    // ── Artifact download ────────────────────────────────────────────────────

    @Test
    @DisplayName("an artifact's content is served, with its digest, after authorization")
    void artifactContentIsServed() throws Exception {
        String runId = runIdOf(trigger("nightly-baseline"));
        reconciler.reconcile();
        reconciler.reconcile();
        reconciler.reconcile();

        String artifacts = mockMvc.perform(get("/api/v2/test-runs/" + runId + "/artifacts")
                        .header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode results = firstArtifactOfType(artifacts, "RESULTS");

        mockMvc.perform(get("/api/v2/test-runs/" + runId + "/artifacts/"
                        + results.get("id").asText() + "/content")
                        .header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                // Attachment, not inline: a runner's own output rendered in the browser by content
                // sniffing would execute in the platform's origin.
                .andExpect(header().string("Content-Disposition",
                        containsString("attachment")))
                .andExpect(header().string("X-Artifact-Digest", results.get("digest").asText()))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().string(containsString("timeStamp,elapsed,label")));
    }

    @Test
    @DisplayName("downloading another team's artifact returns 404, not 403")
    void artifactOfAnotherTeamIsNotFound() throws Exception {
        String runId = runIdOf(trigger("nightly-baseline"));
        reconciler.reconcile();

        String artifacts = mockMvc.perform(get("/api/v2/test-runs/" + runId + "/artifacts")
                        .header("X-API-Key", API_KEY))
                .andReturn().getResponse().getContentAsString();
        String artifactId = firstArtifactOfType(artifacts, "EXECUTION_PLAN").get("id").asText();

        // The other team's key is a different team in the same fixture. 404 rather than 403 so the
        // endpoint cannot be used to discover which run and artifact identifiers are real.
        mockMvc.perform(get("/api/v2/test-runs/" + runId + "/artifacts/" + artifactId + "/content")
                        .header("X-API-Key", OTHER_TEAM_API_KEY))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("an artifact identifier that belongs to no run returns 404")
    void unknownArtifactIsNotFound() throws Exception {
        String runId = runIdOf(trigger("nightly-baseline"));
        reconciler.reconcile();

        mockMvc.perform(get("/api/v2/test-runs/" + runId + "/artifacts/"
                        + UUID.randomUUID() + "/content")
                        .header("X-API-Key", API_KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("artifact.notFound"));
    }

    @Test
    @DisplayName("content that no longer matches its recorded digest is refused, not served")
    void tamperedArtifactIsRefused() throws Exception {
        String runId = runIdOf(trigger("nightly-baseline"));
        reconciler.reconcile();

        String artifacts = mockMvc.perform(get("/api/v2/test-runs/" + runId + "/artifacts")
                        .header("X-API-Key", API_KEY))
                .andReturn().getResponse().getContentAsString();
        JsonNode plan = firstArtifactOfType(artifacts, "EXECUTION_PLAN");

        // Rewritten underneath the platform, as a storage fault or a tamper would. The reference in
        // the database still records the digest of what the run actually executed.
        Path stored = artifactRoot().resolve(plan.get("objectKey").asText());
        Files.writeString(stored, "<jmeterTestPlan>tampered</jmeterTestPlan>");

        mockMvc.perform(get("/api/v2/test-runs/" + runId + "/artifacts/"
                        + plan.get("id").asText() + "/content")
                        .header("X-API-Key", API_KEY))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("artifact.digestMismatch"));
    }

    /**
     * The check that makes persisting the measurements safe.
     *
     * <p>Storing a reduction rather than recomputing it on read is only defensible if the two agree.
     * If they ever diverge — a reducer change, a column that silently narrows a value, a rounding
     * difference — then a stored report and a recomputed one would answer the same question
     * differently, and the whole basis for calling a verdict re-derivable would be gone.
     *
     * <p>This walks the same path a disputed number would: read the stored {@code results.jtl}, parse
     * it with the engine, reduce it, and compare to what the database holds.
     */
    @Test
    @DisplayName("stored measurements equal a fresh recomputation from results.jtl")
    void storedMeasurementsMatchRecomputation() throws Exception {
        String runId = runIdOf(trigger("nightly-baseline"));
        reconciler.reconcile();
        reconciler.reconcile();
        reconciler.reconcile();

        TestRunId id = TestRunId.of(runId);

        List<RunnerOutput.ShardResult> shardResults = new ArrayList<>();
        for (RunnerShard shard : shards.findByRun(id)) {
            ArtifactStore.ArtifactReference results = catalog.artifactsFor(id).stream()
                    .filter(reference -> reference.type() == ArtifactStore.ArtifactType.RESULTS)
                    .filter(reference -> reference.shardId()
                            .map(shardId -> shardId.equals(shard.id())).orElse(false))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Shard produced no results artifact"));

            shardResults.add(new RunnerOutput.ShardResult(shard.shardIndex(),
                    new String(artifactStore.read(results).orElseThrow(), StandardCharsets.UTF_8)));
        }

        int shardsExpected = plans.find(runs.find(id).orElseThrow().executionPlanId().orElseThrow())
                .orElseThrow().capacity().shardCount();

        SampleSet reparsed = engines.require("jmeter")
                .parse(new RunnerOutput(id, shardsExpected, shardResults));

        NormalizedRunResult recomputed = RunResultReducer.reduce(
                id, "jmeter", shardsExpected, shardResults.size(), reparsed);

        assertThat(catalog.runMetricsFor(id))
                .hasValueSatisfying(stored -> assertThat(stored).isEqualTo(recomputed));
    }

    private static JsonNode firstArtifactOfType(String artifactsJson, String type)
            throws IOException {

        for (JsonNode artifact : new ObjectMapper().readTree(artifactsJson)) {
            if (type.equals(artifact.get("type").asText())) {
                return artifact;
            }
        }
        throw new AssertionError("No artifact of type " + type + " in " + artifactsJson);
    }

    private Path artifactRoot() {
        return Path.of(orchestrationProperties.getWorkDirectory(), "artifacts");
    }
}
