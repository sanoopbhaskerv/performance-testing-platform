package com.perfplatform.persistence.dataset;

import com.perfplatform.domain.dataset.DatasetUri;
import com.perfplatform.domain.identity.DatasetId;
import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.port.DatasetCatalog;
import com.perfplatform.domain.port.DatasetCatalog.DatasetVersion;
import com.perfplatform.persistence.definition.TeamEntity;
import com.perfplatform.persistence.definition.TeamJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
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
 * Dataset versions, against real PostgreSQL.
 *
 * <p>The guarantee here is immutability, and it is a database guarantee rather than an application
 * one. A test pinned to {@code dataset://orders/3} must read the same rows in a year, so a second
 * upload of version 3 has to lose — including when two uploads race and both read no existing row
 * before writing. Only a unique constraint can decide that, so it is asserted against the database.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class DatasetCatalogStorePostgresTest {

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
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration/{vendor}");
    }

    private static final Instant NOW = Instant.parse("2026-08-02T09:00:00Z");

    @Autowired
    private DatasetCatalogStore catalog;

    @Autowired
    private TeamJpaRepository teams;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;
    private TeamId team;
    private TeamId otherTeam;

    @BeforeEach
    void seed() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("TRUNCATE TABLE datasets, applications, teams RESTART IDENTITY CASCADE");

        team = TeamId.generate();
        teams.saveAndFlush(new TeamEntity(team.value(), "team-" + shortId(), NOW));

        otherTeam = TeamId.generate();
        teams.saveAndFlush(new TeamEntity(otherTeam.value(), "team-" + shortId(), NOW));
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private DatasetVersion version(TeamId owner, String name, int number) {
        DatasetUri uri = DatasetUri.of(name, number);
        return new DatasetVersion(DatasetId.generate(), owner, uri,
                "teams/%s/datasets/%s/%d/data.csv".formatted(owner.value(), name, number),
                "sha256:abc", List.of("orderId", "customerId"), 42, 1024, "alice@example.com", NOW);
    }

    @Nested
    @DisplayName("round trip")
    class RoundTrip {

        @Test
        @DisplayName("what was registered is what comes back, columns and all")
        void roundTrips() {
            catalog.register(version(team, "orders", 1));

            DatasetVersion read = catalog.find(team, "orders", 1).orElseThrow();

            assertThat(read.uri().toString()).isEqualTo("dataset://orders/1");
            assertThat(read.columns()).containsExactly("orderId", "customerId");
            assertThat(read.rowCount()).isEqualTo(42);
            assertThat(read.sizeBytes()).isEqualTo(1024);
            assertThat(read.uploadedBy()).isEqualTo("alice@example.com");
            assertThat(read.uploadedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("a dataset with no columns round-trips as no columns, not as one blank one")
        void emptyColumns() {
            DatasetVersion empty = new DatasetVersion(DatasetId.generate(), team,
                    DatasetUri.of("orders", 1), "teams/x/datasets/orders/1/data.csv", "sha256:abc",
                    List.of(), 0, 0, "alice@example.com", NOW);
            catalog.register(empty);

            assertThat(catalog.find(team, "orders", 1).orElseThrow().columns()).isEmpty();
        }

        @Test
        @DisplayName("the latest version is reported, and is 0 when there is none")
        void latestVersion() {
            assertThat(catalog.latestVersion(team, "orders")).isZero();

            catalog.register(version(team, "orders", 1));
            catalog.register(version(team, "orders", 2));

            assertThat(catalog.latestVersion(team, "orders")).isEqualTo(2);
            assertThat(catalog.latestVersion(team, "clients")).isZero();
        }
    }

    @Nested
    @DisplayName("immutability")
    class Immutability {

        @Test
        @DisplayName("registering an existing version is refused, never an overwrite")
        void duplicateVersionRefused() {
            catalog.register(version(team, "orders", 1));

            assertThatThrownBy(() -> catalog.register(version(team, "orders", 1)))
                    .isInstanceOf(DatasetCatalog.DatasetVersionExistsException.class);
        }

        /**
         * The application refuses first; this asserts the database refuses too. A row a direct SQL
         * update could change is a test whose inputs changed without its specification changing.
         */
        @Test
        @DisplayName("the database itself rejects an update to a stored version")
        void databaseRejectsUpdate() {
            catalog.register(version(team, "orders", 1));

            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE datasets SET row_count = 0 WHERE name = 'orders'"))
                    .hasMessageContaining("immutable");
        }

        @Test
        @DisplayName("one key holds one object")
        void objectKeyIsUnique() {
            DatasetVersion first = version(team, "orders", 1);
            DatasetVersion sameKey = new DatasetVersion(DatasetId.generate(), team,
                    DatasetUri.of("orders", 2), first.objectKey(), "sha256:def",
                    List.of("orderId"), 1, 1, "bob@example.com", NOW);

            catalog.register(first);

            assertThatThrownBy(() -> catalog.register(sameKey))
                    .isInstanceOf(DatasetCatalog.DatasetVersionExistsException.class);
        }
    }

    @Nested
    @DisplayName("team scoping")
    class TeamScoping {

        @Test
        @DisplayName("two teams may hold the same dataset name without seeing each other's")
        void namesAreScopedToTeam() {
            catalog.register(version(team, "orders", 1));
            catalog.register(version(otherTeam, "orders", 1));

            assertThat(catalog.listForTeam(team)).hasSize(1);
            assertThat(catalog.find(team, "orders", 1).orElseThrow().teamId()).isEqualTo(team);
            assertThat(catalog.find(otherTeam, "orders", 1).orElseThrow().teamId())
                    .isEqualTo(otherTeam);
        }

        @Test
        @DisplayName("a listing is newest version first within each name")
        void listingOrder() {
            catalog.register(version(team, "orders", 1));
            catalog.register(version(team, "orders", 2));

            assertThat(catalog.listForTeam(team))
                    .extracting(DatasetVersion::version)
                    .containsExactly(2, 1);
        }
    }
}
