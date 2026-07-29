package com.perfplatform.persistence.definition;

import com.perfplatform.domain.identity.ApplicationId;
import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.identity.TestDefinitionId;
import com.perfplatform.domain.identity.TestDefinitionVersionId;
import com.perfplatform.domain.spec.SpecificationDigest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for the specification registry against real PostgreSQL.
 *
 * <p>These run against PostgreSQL rather than H2 on purpose. The claims under test — that the
 * Flyway migrations apply cleanly, that JSONB storage works, and above all that a stored version
 * cannot be modified even by a direct SQL write — cannot be demonstrated on H2, which has no
 * JSONB and no plpgsql triggers. Proving them on H2 would prove a weaker statement than the one
 * the platform depends on.
 *
 * <p>Skipped automatically when Docker is unavailable rather than failing, so a developer without
 * Docker still gets a green build; CI must have Docker for these to provide their guarantee.
 */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestDefinitionRegistry.class)
class TestDefinitionRegistryPostgresTest {

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
        // Flyway owns the schema; Hibernate must not touch it.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration/{vendor}");
    }

    @Autowired
    private TestDefinitionRegistry registry;

    @Autowired
    private TeamJpaRepository teams;

    @Autowired
    private ApplicationJpaRepository applications;

    @Autowired
    private TestDefinitionVersionJpaRepository versions;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;
    private TeamId teamId;
    private ApplicationId applicationId;

    private static final String SPECIFICATION = """
            {"apiVersion":"performance.ameriprise.com/v1alpha1","kind":"PerformanceTest",\
            "metadata":{"name":"proposal-baseline","team":"advice","application":"proposal"}}""";

    @BeforeEach
    void seedTeamAndApplication() {
        jdbc = new JdbcTemplate(dataSource);
        Instant now = Instant.parse("2026-07-29T10:15:30Z");

        teamId = TeamId.generate();
        teams.saveAndFlush(new TeamEntity(teamId.value(), "advice-" + shortId(), now));

        applicationId = ApplicationId.generate();
        applications.saveAndFlush(new ApplicationEntity(applicationId.value(), teamId.value(),
                "proposal-" + shortId(), ApplicationEntity.TargetClass.NON_PRODUCTION, now));
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private TestDefinitionEntity newDefinition() {
        return registry.createDefinition(TestDefinitionId.generate(), teamId, applicationId,
                "baseline-" + shortId(), "alice@example.com");
    }

    private TestDefinitionVersionEntity appendVersion(TestDefinitionEntity definition, String body) {
        return registry.appendVersion(
                TestDefinitionId.of(definition.getId()),
                TestDefinitionVersionId.generate(),
                body,
                SpecificationDigest.ofCanonicalForm(body),
                TestDefinitionVersionEntity.ValidationStatus.VALID,
                "alice@example.com");
    }

    @Nested
    @DisplayName("migrations")
    class Migrations {

        @Test
        @DisplayName("every migration applies cleanly and creates the registry tables")
        void migrationsApply() {
            List<String> tables = jdbc.queryForList(
                    "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                    String.class);

            assertThat(tables).contains("teams", "applications", "test_definitions",
                    "test_definition_versions", "v1_test_runs", "flyway_schema_history");
        }

        @Test
        @DisplayName("the specification column is JSONB, so later increments can query inside it")
        void specificationIsJsonb() {
            String type = jdbc.queryForObject("""
                    SELECT data_type FROM information_schema.columns
                    WHERE table_name = 'test_definition_versions' AND column_name = 'specification_json'
                    """, String.class);

            assertThat(type).isEqualTo("jsonb");
        }

        /**
         * Increment 1C gave the V2 orchestration model the {@code test_runs} name and renamed V1's
         * table to {@code v1_test_runs}. The rename must not have reshaped it: V1 keeps working
         * against the same columns, and this asserts that rather than the name alone.
         */
        @Test
        @DisplayName("the V1 run table is reproduced intact so V1 keeps working under Flyway")
        void v1TableIsPreserved() {
            List<String> columns = jdbc.queryForList("""
                    SELECT column_name FROM information_schema.columns WHERE table_name = 'v1_test_runs'
                    """, String.class);

            assertThat(columns).contains("id", "team", "test_type", "status", "sla_result",
                    "started_at", "completed_at", "created_at", "p95latency_ms",
                    "error_rate_percent", "report_path", "failure_reason",
                    "heap_dump_report_path", "thread_dump_report_path");
        }
    }

    @Nested
    @DisplayName("definitions and versions")
    class Versions {

        @Test
        @DisplayName("a new definition starts with no versions")
        void definitionStartsEmpty() {
            TestDefinitionEntity definition = newDefinition();

            assertThat(definition.getLatestVersion()).isZero();
            assertThat(registry.listVersions(TestDefinitionId.of(definition.getId()))).isEmpty();
        }

        @Test
        @DisplayName("versions are numbered contiguously from one and advance the definition pointer")
        void versionsAreContiguous() {
            TestDefinitionEntity definition = newDefinition();

            assertThat(appendVersion(definition, SPECIFICATION).getVersionNumber()).isEqualTo(1);
            assertThat(appendVersion(definition, SPECIFICATION.replace("proposal", "quote"))
                    .getVersionNumber()).isEqualTo(2);

            assertThat(registry.findDefinition(TestDefinitionId.of(definition.getId()))
                    .orElseThrow().getLatestVersion()).isEqualTo(2);
        }

        @Test
        @DisplayName("the stored specification and digest round-trip")
        void storesSpecificationAndDigest() {
            TestDefinitionEntity definition = newDefinition();
            TestDefinitionVersionEntity version = appendVersion(definition, SPECIFICATION);

            TestDefinitionVersionEntity reloaded = versions.findById(version.getId()).orElseThrow();

            assertThat(reloaded.getSpecificationJson()).isEqualTo(SPECIFICATION);
            assertThat(reloaded.getSpecificationDigest())
                    .isEqualTo(SpecificationDigest.ofCanonicalForm(SPECIFICATION).value())
                    .startsWith("sha256:");
            assertThat(reloaded.getValidationStatus())
                    .isEqualTo(TestDefinitionVersionEntity.ValidationStatus.VALID);
            assertThat(reloaded.getApprovalStatus())
                    .isEqualTo(TestDefinitionVersionEntity.ApprovalStatus.NOT_REQUIRED);
        }

        @Test
        @DisplayName("the latest version is retrievable and is the highest numbered")
        void findsLatestVersion() {
            TestDefinitionEntity definition = newDefinition();
            appendVersion(definition, SPECIFICATION);
            appendVersion(definition, SPECIFICATION.replace("proposal", "quote"));

            assertThat(registry.findLatestVersion(TestDefinitionId.of(definition.getId()))
                    .orElseThrow().getVersionNumber()).isEqualTo(2);
        }

        @Test
        @DisplayName("versions are findable by digest, so an unchanged resubmission is detectable")
        void findsByDigest() {
            TestDefinitionEntity definition = newDefinition();
            TestDefinitionVersionEntity version = appendVersion(definition, SPECIFICATION);

            assertThat(registry.findByDigest(SpecificationDigest.ofCanonicalForm(SPECIFICATION)))
                    .extracting(TestDefinitionVersionEntity::getId)
                    .contains(version.getId());
        }

        @Test
        @DisplayName("two teams may each own an application of the same name")
        void applicationNamesAreScopedToTeam() {
            TeamId otherTeam = TeamId.generate();
            teams.saveAndFlush(new TeamEntity(otherTeam.value(), "other-" + shortId(),
                    Instant.parse("2026-07-29T10:15:30Z")));

            assertThat(applications.saveAndFlush(new ApplicationEntity(
                    ApplicationId.generate().value(), otherTeam.value(), "proposal",
                    ApplicationEntity.TargetClass.NON_PRODUCTION,
                    Instant.parse("2026-07-29T10:15:30Z"))))
                    .isNotNull();
        }
    }

    @Nested
    @DisplayName("immutability")
    class Immutability {

        @Test
        @DisplayName("the registry refuses to replace a stored version")
        void registryRefusesReplacement() {
            TestDefinitionEntity definition = newDefinition();
            TestDefinitionVersionEntity version = appendVersion(definition, SPECIFICATION);

            assertThatThrownBy(() -> registry.replaceVersion(version))
                    .isInstanceOf(TestDefinitionRegistry.ImmutableVersionException.class)
                    .hasMessageContaining("immutable");
        }

        /**
         * The guarantee that matters. Application-level checks are bypassed by any direct SQL
         * write — an operator, a migration script, another service sharing the database. Only the
         * trigger stops those, and a run's reproducibility depends on it holding.
         */
        @Test
        @DisplayName("a direct SQL update of the specification is rejected by the database")
        void databaseRejectsDirectSpecificationUpdate() {
            TestDefinitionEntity definition = newDefinition();
            TestDefinitionVersionEntity version = appendVersion(definition, SPECIFICATION);

            // The rejection is the guarantee. The value cannot also be re-read here: PostgreSQL
            // aborts the surrounding transaction once the trigger raises, so any follow-up
            // statement in this test's transaction would fail for an unrelated reason.
            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE test_definition_versions SET specification_json = ?::jsonb WHERE id = ?",
                    "{\"tampered\":true}", version.getId()))
                    .hasMessageContaining("immutable");
        }

        @Test
        @DisplayName("a direct SQL update of the digest is rejected by the database")
        void databaseRejectsDirectDigestUpdate() {
            TestDefinitionEntity definition = newDefinition();
            TestDefinitionVersionEntity version = appendVersion(definition, SPECIFICATION);

            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE test_definition_versions SET specification_digest = ? WHERE id = ?",
                    "sha256:0000000000000000000000000000000000000000000000000000000000000000",
                    version.getId()))
                    .hasMessageContaining("immutable");
        }

        @Test
        @DisplayName("a stored version cannot be deleted")
        void databaseRejectsDelete() {
            TestDefinitionEntity definition = newDefinition();
            TestDefinitionVersionEntity version = appendVersion(definition, SPECIFICATION);

            assertThatThrownBy(() -> jdbc.update(
                    "DELETE FROM test_definition_versions WHERE id = ?", version.getId()))
                    .hasMessageContaining("immutable");
        }

        /**
         * Approval records a decision about a version without changing what the version says, so
         * it is the one permitted update.
         */
        @Test
        @DisplayName("approval is permitted, because it does not change what the version says")
        void approvalIsPermitted() {
            TestDefinitionEntity definition = newDefinition();
            TestDefinitionVersionEntity version = appendVersion(definition, SPECIFICATION);

            version.approve("approver@example.com", Instant.parse("2026-07-29T11:00:00Z"));
            versions.saveAndFlush(version);

            TestDefinitionVersionEntity reloaded = versions.findById(version.getId()).orElseThrow();
            assertThat(reloaded.getApprovalStatus())
                    .isEqualTo(TestDefinitionVersionEntity.ApprovalStatus.APPROVED);
            assertThat(reloaded.getApprovedBy()).isEqualTo("approver@example.com");
            assertThat(reloaded.getSpecificationJson()).isEqualTo(SPECIFICATION);
        }

        @Test
        @DisplayName("a duplicate version number is rejected by the unique constraint")
        void rejectsDuplicateVersionNumber() {
            TestDefinitionEntity definition = newDefinition();
            TestDefinitionVersionEntity first = appendVersion(definition, SPECIFICATION);

            // Simulates a concurrent writer that already claimed version 1.
            assertThatThrownBy(() -> versions.saveAndFlush(new TestDefinitionVersionEntity(
                    UUID.randomUUID(), definition.getId(), first.getVersionNumber(),
                    SPECIFICATION, "sha256:duplicate",
                    TestDefinitionVersionEntity.ValidationStatus.VALID,
                    "bob@example.com", Instant.parse("2026-07-29T10:15:30Z"))))
                    .isInstanceOf(Exception.class);
        }
    }
}
