package com.perfplatform.orchestration.dataset;

import com.perfplatform.domain.identity.ApplicationId;
import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.port.TeamDirectory;
import com.perfplatform.domain.security.CallerContext;
import com.perfplatform.domain.security.Scope;
import com.perfplatform.orchestration.support.OrchestrationFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Uploading the data a test sends.
 *
 * <p>Two properties carry the weight. Versions are <b>allocated, never chosen</b>, so nothing can
 * overwrite the rows a specification is pinned to; and a refused upload <b>stores nothing</b>, so a
 * rejection is not a half-written file with a rejection message on top of it.
 */
class ManageDatasetsUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-08-02T09:00:00Z");
    private static final String TEAM_NAME = "advice";
    private static final long MAX_SIZE = 1024;

    private final TeamId team = TeamId.generate();
    private final TeamId otherTeam = TeamId.generate();

    private final OrchestrationFixtures.InMemoryDatasets datasets =
            new OrchestrationFixtures.InMemoryDatasets();

    private final ManageDatasetsUseCase useCase = new ManageDatasetsUseCase(
            datasets, datasets, teamDirectory(), Clock.fixed(NOW, ZoneOffset.UTC), MAX_SIZE);

    private CallerContext caller(TeamId teamId) {
        return new CallerContext("alice@example.com", Set.of(teamId),
                Set.of(Scope.DEFINITIONS_WRITE, Scope.DEFINITIONS_READ), false);
    }

    private TeamDirectory teamDirectory() {
        return new TeamDirectory() {
            @Override
            public Optional<TeamId> findByName(String name) {
                return TEAM_NAME.equals(name) ? Optional.of(team) : Optional.empty();
            }

            @Override
            public Optional<ApplicationId> findApplication(TeamId teamId, String name) {
                return Optional.empty();
            }
        };
    }

    private static byte[] csv(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }

    @Nested
    @DisplayName("versioning")
    class Versioning {

        @Test
        @DisplayName("the first upload of a name is version 1")
        void firstIsVersionOne() {
            var result = useCase.upload(TEAM_NAME, "orders", csv("orderId\nO-1"), caller(team));

            assertThat(result.version().version()).isEqualTo(1);
            assertThat(result.version().uri().toString()).isEqualTo("dataset://orders/1");
            assertThat(result.version().columns()).containsExactly("orderId");
            assertThat(result.version().rowCount()).isEqualTo(1);
        }

        /**
         * The property the whole design rests on. If a second upload replaced version 1, a test
         * pinned to {@code dataset://orders/1} would start sending different rows with nothing in
         * its own history saying so.
         */
        @Test
        @DisplayName("uploading again adds a version and leaves the old one untouched")
        void secondAddsAVersion() {
            useCase.upload(TEAM_NAME, "orders", csv("orderId\nO-1"), caller(team));
            var second = useCase.upload(TEAM_NAME, "orders", csv("orderId\nO-2\nO-3"),
                    caller(team));

            assertThat(second.version().version()).isEqualTo(2);
            assertThat(useCase.get(TEAM_NAME, "orders", 1, caller(team)).rowCount()).isEqualTo(1);
            assertThat(useCase.get(TEAM_NAME, "orders", 2, caller(team)).rowCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("versions are per name, not global")
        void versionsArePerName() {
            useCase.upload(TEAM_NAME, "orders", csv("orderId\nO-1"), caller(team));
            var clients = useCase.upload(TEAM_NAME, "clients", csv("clientId\nC-1"), caller(team));

            assertThat(clients.version().version()).isEqualTo(1);
        }

        @Test
        @DisplayName("who uploaded it is part of the record, not only of a log")
        void recordsUploader() {
            var result = useCase.upload(TEAM_NAME, "orders", csv("orderId\nO-1"), caller(team));

            assertThat(result.version().uploadedBy()).isEqualTo("alice@example.com");
            assertThat(result.version().uploadedAt()).isEqualTo(NOW);
        }
    }

    @Nested
    @DisplayName("refusals")
    class Refusals {

        @Test
        @DisplayName("a credential column is refused and nothing is stored")
        void credentialColumnStoresNothing() {
            assertThatThrownBy(() -> useCase.upload(TEAM_NAME, "logins",
                    csv("username,password\na.smith,Sunflower2019!"), caller(team)))
                    .isInstanceOf(ManageDatasetsUseCase.DatasetRejectedException.class);

            assertThat(datasets.listForTeam(team)).isEmpty();
            assertThat(datasets.read("teams/" + team.value() + "/datasets/logins/1/data.csv"))
                    .isEmpty();
        }

        @Test
        @DisplayName("an upload over the size cap is refused, and the message says why it exists")
        void tooLarge() {
            String big = "orderId\n" + "O-1\n".repeat(1000);

            assertThatThrownBy(() -> useCase.upload(TEAM_NAME, "orders", csv(big), caller(team)))
                    .isInstanceOf(ManageDatasetsUseCase.DatasetRejectedException.class)
                    .satisfies(thrown -> assertThat(
                            ((ManageDatasetsUseCase.DatasetRejectedException) thrown)
                                    .findings().codes())
                            .contains("dataset.tooLarge"));

            assertThat(datasets.listForTeam(team)).isEmpty();
        }

        @Test
        @DisplayName("an empty upload is refused")
        void empty() {
            assertThatThrownBy(() -> useCase.upload(TEAM_NAME, "orders", new byte[0], caller(team)))
                    .isInstanceOf(ManageDatasetsUseCase.DatasetRejectedException.class);
        }

        /** A name reaches an object key, which is what makes authorization structural. */
        @Test
        @DisplayName("a name that could escape its prefix is refused")
        void unusableName() {
            assertThatThrownBy(() -> useCase.upload(TEAM_NAME, "../other", csv("a\n1"),
                    caller(team)))
                    .isInstanceOf(ManageDatasetsUseCase.DatasetRejectedException.class);

            assertThatThrownBy(() -> useCase.upload(TEAM_NAME, "Orders", csv("a\n1"), caller(team)))
                    .isInstanceOf(ManageDatasetsUseCase.DatasetRejectedException.class);
        }

        @Test
        @DisplayName("a team the registry does not know is refused, never created")
        void unknownTeam() {
            assertThatThrownBy(() -> useCase.upload("no-such-team", "orders", csv("a\n1"),
                    caller(team)))
                    .isInstanceOf(ManageDatasetsUseCase.UnknownTeamException.class);
        }
    }

    @Nested
    @DisplayName("authorization")
    class Authorization {

        @Test
        @DisplayName("a caller outside the owning team cannot upload to it")
        void cannotUploadToAnotherTeam() {
            assertThatThrownBy(() -> useCase.upload(TEAM_NAME, "orders", csv("orderId\nO-1"),
                    caller(otherTeam)))
                    .isInstanceOf(CallerContext.AuthorizationException.class);
        }

        @Test
        @DisplayName("uploading requires definitions:write, not merely membership")
        void requiresWriteScope() {
            CallerContext readOnly = new CallerContext("bob@example.com", Set.of(team),
                    Set.of(Scope.DEFINITIONS_READ), false);

            assertThatThrownBy(() -> useCase.upload(TEAM_NAME, "orders", csv("orderId\nO-1"),
                    readOnly))
                    .isInstanceOf(CallerContext.AuthorizationException.class);
        }

        @Test
        @DisplayName("a listing shows only the caller's own teams")
        void listingIsScoped() {
            useCase.upload(TEAM_NAME, "orders", csv("orderId\nO-1"), caller(team));

            assertThat(useCase.listForCaller(caller(team))).hasSize(1);
            assertThat(useCase.listForCaller(caller(otherTeam))).isEmpty();
        }

        @Test
        @DisplayName("reading one version is authorized against the owning team")
        void readIsScoped() {
            useCase.upload(TEAM_NAME, "orders", csv("orderId\nO-1"), caller(team));

            assertThatThrownBy(() -> useCase.get(TEAM_NAME, "orders", 1, caller(otherTeam)))
                    .isInstanceOf(CallerContext.AuthorizationException.class);
        }

        @Test
        @DisplayName("a version the team does not have is not found")
        void unknownVersion() {
            useCase.upload(TEAM_NAME, "orders", csv("orderId\nO-1"), caller(team));

            assertThatThrownBy(() -> useCase.get(TEAM_NAME, "orders", 2, caller(team)))
                    .isInstanceOf(ManageDatasetsUseCase.UnknownDatasetException.class);
        }
    }
}
