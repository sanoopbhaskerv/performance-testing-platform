package com.perfplatform.api.v2;

import com.perfplatform.api.v2.dto.ValidatorResponse;
import com.perfplatform.api.v2.error.V2ProblemHandler;
import com.perfplatform.api.v2.security.CallerContextProvider;
import com.perfplatform.api.v2.spec.SpecificationParser;
import com.perfplatform.api.v2.spec.SpecificationValidationService;
import com.perfplatform.api.v2.validator.CorrelationBaseline;
import com.perfplatform.api.v2.validator.SpecificationDryRunService;
import com.perfplatform.domain.identity.RecorderSessionId;
import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.importer.SourceImporter;
import com.perfplatform.domain.port.EngineCatalog;
import com.perfplatform.domain.security.CallerContext;
import com.perfplatform.domain.security.Scope;
import com.perfplatform.domain.validation.ValidationResult;
import com.perfplatform.orchestration.recorder.ManageRecordingsUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The recorder's Validator: the same live iteration, plus a verdict on every correlation.
 */
class RecordingValidatorControllerTest {

    private static final EngineCatalog ENGINES = () -> Set.of("jmeter", "playwright");
    private static final RecorderSessionId SESSION = RecorderSessionId.generate();

    private final ManageRecordingsUseCase recordings = mock(ManageRecordingsUseCase.class);
    private final SpecificationDryRunService dryRun = mock(SpecificationDryRunService.class);
    private final CallerContextProvider callers = mock(CallerContextProvider.class);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        SpecificationValidationService validation =
                new SpecificationValidationService(new SpecificationParser(), ENGINES);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new RecordingValidatorController(recordings, validation, dryRun,
                        callers))
                .setControllerAdvice(new V2ProblemHandler())
                .build();
        when(callers.current()).thenReturn(new CallerContext("alice@example.com",
                Set.of(TeamId.generate()),
                Set.of(Scope.RUNS_EXECUTE, Scope.RECORDINGS_READ), false));
    }

    @Test
    @DisplayName("the literals the recording captured reach the dry run as a baseline")
    void passesTheRecordedLiteralsToTheDryRun() throws Exception {
        when(recordings.draft(any(), any())).thenReturn(recordingWithCorrelation());
        when(dryRun.validate(any(), any(ValidationResult.class), any(CallerContext.class),
                any(CorrelationBaseline.class)))
                .thenReturn(provenResponse());

        mockMvc.perform(post("/api/v2/recordings/" + SESSION.value() + "/validator")
                        .contentType("application/yaml")
                        .content(validDocument()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.journeys[0].correlations[0].variable").value("session"))
                .andExpect(jsonPath("$.journeys[0].correlations[0].verdict").value("proven"))
                .andExpect(jsonPath("$.journeys[0].correlations[0].usedBySteps[0]")
                        .value("create-order"));

        ArgumentCaptor<CorrelationBaseline> baseline =
                ArgumentCaptor.forClass(CorrelationBaseline.class);
        verify(dryRun).validate(any(), any(ValidationResult.class), any(CallerContext.class),
                baseline.capture());
        assertThat(baseline.getValue().literal("open", "session"))
                .contains("the-token-that-was-recorded");
    }

    /**
     * A document that was never going to execute must not reach the recorder.
     *
     * <p>Reading a recording is an authorized act against somebody's captured session. The dry run
     * refuses an invalid specification without sending anything, so fetching the recording first
     * would perform that read on behalf of a request with no work to do — and would make a junk
     * body a way to probe which session ids exist.
     */
    @Test
    @DisplayName("a document that could not execute anyway never reads the recording")
    void invalidSpecificationNeverReadsTheRecording() throws Exception {
        mockMvc.perform(post("/api/v2/recordings/" + SESSION.value() + "/validator")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("not: a performance test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.journeys").isEmpty())
                .andExpect(jsonPath("$.findings[0].severity").value("ERROR"));

        verify(recordings, never()).draft(any(), any());
        verify(dryRun, never()).validate(any(), any(ValidationResult.class),
                any(CallerContext.class), any(CorrelationBaseline.class));
    }

    @Test
    @DisplayName("a recording with no correlations still validates, with an empty baseline")
    void emptyBaselineIsAllowed() throws Exception {
        when(recordings.draft(any(), any())).thenReturn(
                SourceImporter.ImportResult.of(List.of(), ValidationResult.valid(), List.of()));
        when(dryRun.validate(any(), any(ValidationResult.class), any(CallerContext.class),
                any(CorrelationBaseline.class)))
                .thenReturn(new ValidatorResponse(true, "http://example.test", List.of(),
                        List.of()));

        mockMvc.perform(post("/api/v2/recordings/" + SESSION.value() + "/validator")
                        .contentType("application/yaml")
                        .content(validDocument()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));

        ArgumentCaptor<CorrelationBaseline> baseline =
                ArgumentCaptor.forClass(CorrelationBaseline.class);
        verify(dryRun).validate(any(), any(ValidationResult.class), any(CallerContext.class),
                baseline.capture());
        assertThat(baseline.getValue().isEmpty()).isTrue();
    }

    private static SourceImporter.ImportResult recordingWithCorrelation() {
        return SourceImporter.ImportResult.of(List.of(), ValidationResult.valid(),
                List.of(new SourceImporter.CorrelationSuggestion(
                        "open:login:session", "open", "session",
                        new SourceImporter.ExtractionEvidence("login", "json-body", "$.token",
                                "the-token..."),
                        List.of(new SourceImporter.ReplacementEvidence("create-order", "query",
                                "session", "the-token-that-was-recorded")))));
    }

    private static ValidatorResponse provenResponse() {
        return new ValidatorResponse(true, "http://example.test", List.of(),
                List.of(new ValidatorResponse.JourneyResult("open", true, List.of(),
                        List.of(new ValidatorResponse.CorrelationProof("session", "login",
                                List.of("create-order"), "proven", "fresh-token",
                                "A different value from the one recorded.")))));
    }

    private static String validDocument() {
        return """
                apiVersion: performance.ameriprise.com/v1alpha1
                kind: PerformanceTest
                metadata:
                  name: checkout
                  team: local-dev
                  application: demo-service
                spec:
                  environment:
                    name: performance
                    runnerCell: local
                    targetClass: non-production
                  workloads:
                    - name: api-load
                      type: protocol
                      engine: jmeter
                      model:
                        type: stages
                        stages:
                          - duration: 1s
                            targetUsers: 1
                      scenarios:
                        - name: open
                          weight: 100
                          method: GET
                          path: /api
                """;
    }
}
