package com.perfplatform.api.v2;

import com.perfplatform.domain.execution.ExecutionPhase;
import com.perfplatform.domain.execution.ExecutionProvider;
import com.perfplatform.domain.execution.ProvisionRequest;
import com.perfplatform.domain.run.RunnerShard.ExecutionHandle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The endpoint that tells a consumer whether anything else they see can be believed.
 *
 * <p>Both cases are asserted because the wrong answer in either direction is serious: telling a
 * consumer their numbers are real when nothing ran, or telling them a real run was invented.
 */
class PlatformCapabilityControllerTest {

    /** A provider that answers only the two questions this endpoint asks. */
    private record StubProvider(String id, boolean realLoad) implements ExecutionProvider {

        @Override
        public String providerId() {
            return id;
        }

        @Override
        public boolean generatesRealLoad() {
            return realLoad;
        }

        @Override
        public ProvisioningResult provision(ProvisionRequest request) {
            throw new UnsupportedOperationException("Not exercised by this endpoint");
        }

        @Override
        public ExecutionStatus inspect(ExecutionHandle handle) {
            return ExecutionStatus.of(ExecutionPhase.UNKNOWN);
        }

        @Override
        public CancellationResult cancel(ExecutionHandle handle) {
            throw new UnsupportedOperationException("Not exercised by this endpoint");
        }
    }

    private static MockMvc mockMvcFor(ExecutionProvider provider) {
        return MockMvcBuilders
                .standaloneSetup(new PlatformCapabilityController(provider))
                .build();
    }

    @Test
    @DisplayName("a provider that generates no load says so, unambiguously")
    void syntheticProviderIsDisclosed() throws Exception {
        mockMvcFor(new StubProvider("fake", false))
                .perform(get("/api/v2/platform/execution"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providerId").value("fake"))
                .andExpect(jsonPath("$.generatesRealLoad").value(false))
                // The summary is what a console renders verbatim, so its content is part of the
                // contract rather than incidental prose.
                .andExpect(jsonPath("$.summary").value(containsString("No load is generated")))
                .andExpect(jsonPath("$.summary").value(containsString("describe nothing")));
    }

    @Test
    @DisplayName("a provider that generates load reports it, and names itself")
    void realProviderIsReported() throws Exception {
        mockMvcFor(new StubProvider("local-jmeter", true))
                .perform(get("/api/v2/platform/execution"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providerId").value("local-jmeter"))
                .andExpect(jsonPath("$.generatesRealLoad").value(true))
                .andExpect(jsonPath("$.summary").value(containsString("local-jmeter")));
    }
}
