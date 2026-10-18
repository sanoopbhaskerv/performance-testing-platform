package com.perfplatform.api.v2;

import com.perfplatform.api.v2.error.V2ProblemHandler;
import com.perfplatform.api.v2.security.CallerContextProvider;
import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.port.BrowserSourceStore;
import com.perfplatform.domain.port.TeamDirectory;
import com.perfplatform.domain.security.CallerContext;
import com.perfplatform.domain.security.Scope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.Optional;
import java.util.Set;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BrowserSourceControllerTest {
    final TeamId team = TeamId.generate();
    final BrowserSourceStore store = mock(BrowserSourceStore.class);
    final TeamDirectory teams = mock(TeamDirectory.class);
    final CallerContextProvider callers = mock(CallerContextProvider.class);
    MockMvc mvc;
    @BeforeEach void setup() {
        when(teams.findByName("team-a")).thenReturn(Optional.of(team));
        when(callers.current()).thenReturn(new CallerContext("author", Set.of(team), Set.of(Scope.DEFINITIONS_WRITE), true));
        mvc = MockMvcBuilders.standaloneSetup(new BrowserSourceController(store, teams, callers))
                .setControllerAdvice(new V2ProblemHandler()).build();
    }
    @Test void storesReviewedScriptWithoutExecution() throws Exception {
        mvc.perform(post("/api/v2/browser-sources?team=team-a&reviewed=true")
                .contentType("text/plain").content("import {test} from '@playwright/test';"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.digest").value(org.hamcrest.Matchers.startsWith("sha256:")))
                .andExpect(jsonPath("$.uri").value(org.hamcrest.Matchers.startsWith("artifact://teams/" + team)));
        verify(store).put(eq(team), anyString(), any());
    }
    @Test void refusesUnreviewedSource() throws Exception {
        mvc.perform(post("/api/v2/browser-sources?team=team-a").contentType("text/plain").content("source"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(store);
    }
    @Test void refusesAnotherTeamsWriter() throws Exception {
        when(callers.current()).thenReturn(new CallerContext("other", Set.of(TeamId.generate()), Set.of(Scope.DEFINITIONS_WRITE), true));
        mvc.perform(post("/api/v2/browser-sources?team=team-a&reviewed=true").contentType("text/plain").content("source"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(store);
    }
    @Test void capsUploadSize() throws Exception {
        mvc.perform(post("/api/v2/browser-sources?team=team-a&reviewed=true").contentType("text/plain").content("s".repeat(1024 * 1024 + 1)))
                .andExpect(status().isPayloadTooLarge());
        verifyNoInteractions(store);
    }
}
