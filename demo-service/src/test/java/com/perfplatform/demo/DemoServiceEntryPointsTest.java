package com.perfplatform.demo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What a browser gets when it arrives, rather than what a client gets when it calls.
 *
 * <p>These matter because the recorder starts a session at the target's base URL. A service that
 * answers its own root with a 500 makes a healthy recording produce nothing — failed exchanges are
 * dropped from a draft — and makes the platform look broken when it is reporting accurately.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DemoServiceEntryPointsTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("the root serves a page with links, so a recording has something to click")
    void rootServesALandingPage() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("Student registry")))
                .andExpect(content().string(containsString("href=\"/api/students\"")));
    }

    /**
     * The bug this test exists for: a blanket {@code @ExceptionHandler(Exception.class)} caught
     * Spring's own {@code NoResourceFoundException} and reported an ordinary 404 as a 500.
     */
    @Test
    @DisplayName("an unmapped path is 404, not 500")
    void unmappedPathIsNotFound() throws Exception {
        mockMvc.perform(get("/no-such-page"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a wrong verb is 405, not 500")
    void wrongMethodIsMethodNotAllowed() throws Exception {
        mockMvc.perform(delete("/api/students"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    @DisplayName("a genuinely unknown failure is still a 500 — the catch-all still catches")
    void unknownFailureIsStillServerError() throws Exception {
        // A non-numeric id fails path-variable conversion, which is not an ErrorResponse and so
        // still lands in the catch-all. The point is that the catch-all was narrowed, not removed.
        mockMvc.perform(get("/api/students/not-a-number"))
                .andExpect(status().is5xxServerError());
    }
}
