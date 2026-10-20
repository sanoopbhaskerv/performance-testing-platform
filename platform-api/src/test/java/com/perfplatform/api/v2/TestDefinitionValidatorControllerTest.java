package com.perfplatform.api.v2;

import com.perfplatform.api.v2.dto.ValidatorResponse;
import com.perfplatform.api.v2.error.V2ProblemHandler;
import com.perfplatform.api.v2.security.CallerContextProvider;
import com.perfplatform.api.v2.spec.SpecificationParser;
import com.perfplatform.api.v2.spec.SpecificationValidationService;
import com.perfplatform.api.v2.validator.SpecificationDryRunService;
import com.perfplatform.domain.security.CallerContext;
import com.perfplatform.domain.security.Scope;
import com.perfplatform.domain.validation.ValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TestDefinitionValidatorControllerTest {

    /**
     * The engines a test deployment has.
     *
     * <p>Both, because the shipped examples use both and a validator that reported the browser
     * engine as unavailable would fail every browser fixture for the wrong reason.
     */
    private static final com.perfplatform.domain.port.EngineCatalog ENGINES =
            () -> java.util.Set.of("jmeter", "playwright");

    private final SpecificationDryRunService dryRun = mock(SpecificationDryRunService.class);
    private final CallerContextProvider callers = mock(CallerContextProvider.class);
    private final CallerContext caller = new CallerContext("alice@example.com", Set.of(),
            Set.of(Scope.RUNS_EXECUTE), false);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        SpecificationValidationService validation =
                new SpecificationValidationService(new SpecificationParser(), ENGINES);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new TestDefinitionValidatorController(validation, dryRun, callers))
                .setControllerAdvice(new V2ProblemHandler())
                .build();
        when(callers.current()).thenReturn(caller);
    }

    @Test
    @DisplayName("POST /api/v2/test-definitions/validator returns dry-run evidence")
    void returnsDryRunEvidence() throws Exception {
        when(dryRun.validate(any(), any(ValidationResult.class), any(CallerContext.class)))
                .thenReturn(new ValidatorResponse(true, "http://example.test",
                        List.of(),
                        List.of(new ValidatorResponse.JourneyResult("checkout", true,
                                List.of(new ValidatorResponse.StepResult("open", "GET", "/api",
                                        true, 12,
                                        new ValidatorResponse.RequestEvidence(
                                                "http://example.test/api", Map.of(), null),
                                        new ValidatorResponse.ResponseEvidence(
                                                200, Map.of(), "{}"),
                                        List.of(), List.of(), null))))));

        mockMvc.perform(post("/api/v2/test-definitions/validator")
                        .contentType("application/yaml")
                        .content(validDocument()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.target").value("http://example.test"))
                .andExpect(jsonPath("$.journeys[0].name").value("checkout"))
                .andExpect(jsonPath("$.journeys[0].steps[0].response.status").value(200));

        verify(dryRun).validate(any(), any(ValidationResult.class), any(CallerContext.class));
    }

    @Test
    @DisplayName("invalid specifications return findings and execute nothing")
    void invalidSpecificationDoesNotExecute() throws Exception {
        mockMvc.perform(post("/api/v2/test-definitions/validator")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("not: a performance test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.journeys").isEmpty())
                .andExpect(jsonPath("$.findings[0].severity").value("ERROR"));
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
