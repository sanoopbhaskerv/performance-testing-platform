package com.perfplatform.api.v2;

import com.perfplatform.api.v2.error.V2ProblemHandler;
import com.perfplatform.api.v2.security.CallerContextProvider;
import com.perfplatform.domain.dataset.DatasetUri;
import com.perfplatform.domain.identity.DatasetId;
import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.port.DatasetCatalog.DatasetVersion;
import com.perfplatform.domain.security.CallerContext;
import com.perfplatform.domain.security.Scope;
import com.perfplatform.domain.validation.ValidationFinding;
import com.perfplatform.domain.validation.ValidationResult;
import com.perfplatform.orchestration.dataset.ManageDatasetsUseCase;
import com.perfplatform.orchestration.dataset.ManageDatasetsUseCase.UploadResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The dataset endpoints at the HTTP boundary.
 *
 * <p>Two properties are asserted as a caller sees them. The response carries the
 * {@code dataset://name/version} uri, because there is nowhere else to learn the version that was
 * just allocated. And it carries <b>no rows</b> — this is business data, and a response that
 * included "just a sample to confirm it worked" would put customer records into a response body, a
 * browser cache and every log in between.
 */
class DatasetControllerTest {

    private static final Instant NOW = Instant.parse("2026-08-02T09:00:00Z");
    private static final TeamId TEAM = TeamId.generate();

    private final ManageDatasetsUseCase datasets = mock(ManageDatasetsUseCase.class);
    private final CallerContextProvider callers = mock(CallerContextProvider.class);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new DatasetController(datasets, callers))
                .setControllerAdvice(new V2ProblemHandler())
                .build();

        when(callers.current()).thenReturn(new CallerContext("alice@example.com", Set.of(TEAM),
                Set.of(Scope.DEFINITIONS_WRITE, Scope.DEFINITIONS_READ), false));
    }

    private static DatasetVersion stored() {
        return new DatasetVersion(DatasetId.generate(), TEAM, DatasetUri.of("proposal-clients", 3),
                "teams/t/datasets/proposal-clients/3/data.csv", "sha256:abc",
                List.of("orderId", "customerId"), 2, 40, "alice@example.com", NOW);
    }

    @Test
    @DisplayName("an upload returns 201, the allocated version and the uri to put in a spec")
    void uploadReturnsTheReference() throws Exception {
        when(datasets.upload(anyString(), anyString(), any(), any()))
                .thenReturn(new UploadResult(stored(), ValidationResult.valid()));

        mockMvc.perform(post("/api/v2/datasets")
                        .param("team", "advice")
                        .param("name", "proposal-clients")
                        .contentType("text/csv")
                        .content("orderId,customerId\nO-1,C-1\nO-2,C-2\n"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location",
                        "/api/v2/datasets/advice/proposal-clients/3"))
                .andExpect(jsonPath("$.uri").value("dataset://proposal-clients/3"))
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.columns[0]").value("orderId"))
                .andExpect(jsonPath("$.rowCount").value(2));
    }

    @Test
    @DisplayName("the response carries column names and counts, never a row")
    void responseCarriesNoRows() throws Exception {
        when(datasets.upload(anyString(), anyString(), any(), any()))
                .thenReturn(new UploadResult(stored(), ValidationResult.valid()));

        String body = mockMvc.perform(post("/api/v2/datasets")
                        .param("team", "advice")
                        .param("name", "proposal-clients")
                        .contentType("text/csv")
                        .content("orderId,customerId\nO-1,C-1\n"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("O-1").doesNotContain("C-1");
    }

    @Test
    @DisplayName("findings from an accepted upload are returned, so a caveat is not lost")
    void acceptedUploadStillCarriesFindings() throws Exception {
        when(datasets.upload(anyString(), anyString(), any(), any()))
                .thenReturn(new UploadResult(stored(), ValidationResult.of(
                        ValidationFinding.warning("dataset.rows.empty", "/", "No rows.", "Add some."))));

        mockMvc.perform(post("/api/v2/datasets")
                        .param("team", "advice")
                        .param("name", "proposal-clients")
                        .contentType("text/csv")
                        .content("orderId"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.findings[0].code").value("dataset.rows.empty"))
                .andExpect(jsonPath("$.findings[0].severity").value("WARNING"));
    }

    /**
     * The refusal a consumer is most likely to hit, and the one whose wording matters most: it has
     * to name the column so the file can be fixed, and never the value, because a problem body is
     * returned over HTTP and written to a log.
     */
    @Test
    @DisplayName("a refused upload is 422 with findings, and echoes no value from the file")
    void refusedUploadIsUnprocessable() throws Exception {
        when(datasets.upload(anyString(), anyString(), any(), any()))
                .thenThrow(new ManageDatasetsUseCase.DatasetRejectedException(
                        "The uploaded dataset was not stored.",
                        ValidationResult.of(ValidationFinding.error("dataset.column.sensitive",
                                "/columns", "The column 'password' is named as a credential.",
                                "Remove the column."))));

        String body = mockMvc.perform(post("/api/v2/datasets")
                        .param("team", "advice")
                        .param("name", "logins")
                        .contentType("text/csv")
                        .content("username,password\na.smith,Sunflower2019!"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("dataset.rejected"))
                .andExpect(jsonPath("$.findings[0].code").value("dataset.column.sensitive"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("Sunflower2019!").doesNotContain("a.smith");
        assertThat(body).contains("Nothing was stored");
    }

    @Test
    @DisplayName("a version the team does not have is 404")
    void unknownVersionIsNotFound() throws Exception {
        when(datasets.get(anyString(), anyString(), anyInt(), any()))
                .thenThrow(new ManageDatasetsUseCase.UnknownDatasetException("orders", 9));

        mockMvc.perform(get("/api/v2/datasets/advice/orders/9"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("dataset.notFound"));
    }

    @Test
    @DisplayName("a caller outside the owning team is refused")
    void otherTeamIsForbidden() throws Exception {
        when(datasets.get(anyString(), anyString(), anyInt(), any()))
                .thenThrow(new CallerContext.AuthorizationException(
                        "alice@example.com", Scope.DEFINITIONS_READ));

        mockMvc.perform(get("/api/v2/datasets/other/orders/1"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a listing returns the caller's datasets")
    void listing() throws Exception {
        when(datasets.listForCaller(any())).thenReturn(List.of(stored()));

        mockMvc.perform(get("/api/v2/datasets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("proposal-clients"))
                .andExpect(jsonPath("$[0].uri").value("dataset://proposal-clients/3"));
    }

    private static org.assertj.core.api.AbstractStringAssert<?> assertThat(String actual) {
        return org.assertj.core.api.Assertions.assertThat(actual);
    }
}
