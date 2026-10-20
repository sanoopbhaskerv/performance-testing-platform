package com.perfplatform.v2;

import com.perfplatform.domain.importer.SourceImporter;
import com.perfplatform.domain.recorder.RecorderAddressAllowances;
import com.perfplatform.domain.recorder.RecorderCapture;
import com.perfplatform.domain.recorder.RecorderOrigin;
import com.perfplatform.domain.recorder.RecorderSessionPolicy;
import com.perfplatform.domain.recorder.RecorderViewport;
import com.perfplatform.domain.spec.PerformanceTestSpecification.ScenarioSpec;
import com.perfplatform.domain.validation.ValidationFinding;
import com.perfplatform.importer.har.HarImporter;
import com.perfplatform.orchestration.recorder.LiveRecorderSession;
import com.perfplatform.orchestration.recorder.RecorderBridgeSettings;
import com.perfplatform.orchestration.recorder.RecorderHarRenderer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A recording becomes draft scenarios, through the real HAR importer.
 *
 * <p>This lives in the composition root because it is the only module that has both halves. The
 * orchestration tests use a fake importer to check what is <em>handed over</em>; this checks what
 * comes back, and it is the test that would fail if the rendered document and the importer's
 * expectations ever drifted apart — which is the entire risk of routing a recording through an
 * intermediate format.
 */
class RecordingToDraftTest {

    private static final Instant NOW = Instant.parse("2026-08-06T10:00:00Z");
    private static final HarImporter IMPORTER = new HarImporter();

    @Nested
    @DisplayName("what a person recorded")
    class WhatWasRecorded {

        @Test
        @DisplayName("becomes an ordered journey, one scenario per page visited")
        void becomesOrderedJourneys() {
            SourceImporter.ImportResult result = draftFrom(session -> {
                capture(session, "GET", "https://shop.internal/", 200, "page-1", "text/html");
                capture(session, "GET", "https://shop.internal/api/catalogue", 200, "page-1",
                        "application/json");
                capture(session, "GET", "https://shop.internal/checkout", 200, "page-2", "text/html");
                capture(session, "POST", "https://shop.internal/api/orders", 201, "page-2",
                        "application/json");
            });

            assertThat(result.scenarios()).hasSize(2);
            assertThat(result.scenarios())
                    .allSatisfy(scenario -> assertThat(scenario.steps()).hasSize(2));

            ScenarioSpec first = result.scenarios().get(0);
            assertThat(first.steps()).extracting(step -> step.method() + " " + step.path())
                    .containsExactly("GET /", "GET /api/catalogue");
        }

        @Test
        @DisplayName("is named for what it did, not page-2")
        void journeysAreNamedForWhatTheyDid() {
            SourceImporter.ImportResult result = draftFrom(session -> {
                capture(session, "GET", "https://shop.internal/api/catalogue", 200, "page-1",
                        "application/json");
                capture(session, "POST", "https://shop.internal/api/orders", 201, "page-2",
                        "application/json");
            });

            assertThat(result.scenarios()).extracting(ScenarioSpec::name)
                    .allSatisfy(name -> assertThat(name).doesNotStartWith("page-"))
                    .anySatisfy(name -> assertThat(name).contains("orders"));
        }

        @Test
        @DisplayName("carries no host — where traffic goes is the target registry's decision")
        void hostIsDropped() {
            SourceImporter.ImportResult result = draftFrom(session ->
                    capture(session, "GET", "https://shop.internal/api/orders", 200, "page-1",
                            "application/json"));

            assertThat(result.scenarios()).isNotEmpty();
            assertThat(result.scenarios().toString()).doesNotContain("shop.internal");
        }

        @Test
        @DisplayName("keeps the query string, which is half of what many API calls say")
        void queryIsPreserved() {
            SourceImporter.ImportResult result = draftFrom(session ->
                    capture(session, "GET", "https://shop.internal/api/search?q=hat&page=2", 200,
                            "page-1", "application/json"));

            ScenarioSpec scenario = result.scenarios().get(0);
            assertThat(scenario.steps().get(0).query())
                    .containsEntry("q", "hat")
                    .containsEntry("page", "2");
        }
    }

    @Nested
    @DisplayName("what the importer removes on the way")
    class WhatIsRemoved {

        /**
         * The recorder already skips images and fonts, but a document that redirects or a JSON
         * endpoint serving a stylesheet still reaches it. The importer stripping again is what makes
         * the two layers agree rather than each assuming the other did it.
         */
        @Test
        @DisplayName("static assets, so a journey is requests rather than page furniture")
        void assetsAreStripped() {
            SourceImporter.ImportResult result = draftFrom(session -> {
                capture(session, "GET", "https://shop.internal/api/orders", 200, "page-1",
                        "application/json");
                capture(session, "GET", "https://shop.internal/assets/app.css", 200, "page-1",
                        "text/css");
                capture(session, "GET", "https://shop.internal/assets/logo.png", 200, "page-1",
                        "image/png");
            });

            assertThat(result.scenarios().get(0).steps()).hasSize(1);
            assertThat(result.scenarios().toString())
                    .doesNotContain("app.css")
                    .doesNotContain("logo.png");
        }

        @Test
        @DisplayName("failed exchanges, which would otherwise be measured as the error path")
        void failuresAreDropped() {
            SourceImporter.ImportResult result = draftFrom(session -> {
                capture(session, "GET", "https://shop.internal/api/orders", 200, "page-1",
                        "application/json");
                capture(session, "GET", "https://shop.internal/api/gone", 404, "page-1",
                        "application/json");
            });

            assertThat(result.scenarios().get(0).steps()).hasSize(1);
            assertThat(result.scenarios().toString()).doesNotContain("/api/gone");
        }

        @Test
        @DisplayName("an unobserved status, which must not be treated as a success")
        void unobservedStatusIsNotImported() {
            SourceImporter.ImportResult result = draftFrom(session ->
                    capture(session, "GET", "https://shop.internal/api/pending", 0, "page-1",
                            "application/json"));

            assertThat(result.scenarios()).isEmpty();
        }

        /**
         * The sanitizer already removed this before it reached the buffer. Asserting it again here
         * is the point: the draft is the thing a person will save, and no credential may reach it
         * through any of the layers it passed.
         */
        @Test
        @DisplayName("credentials, at every layer, so none reaches the draft")
        void credentialsNeverReachTheDraft() {
            SourceImporter.ImportResult result = draftFrom(session ->
                    session.onCapture(new RecorderCapture("POST",
                            "https://shop.internal/api/session",
                            Map.of("authorization", "Bearer super-secret-token",
                                    "accept", "application/json"),
                            "application/json", "{\"password\":\"hunter2\"}",
                            Map.of(), "application/json", "{}", null, 200, "page-1"), NOW));

            assertThat(result.scenarios().toString())
                    .doesNotContain("super-secret-token")
                    .doesNotContain("hunter2");
        }
    }

    @Nested
    @DisplayName("what the draft tells the person")
    class WhatIsReported {

        @Test
        @DisplayName("a multi-step journey needs correlation before it will do anything real")
        void correlationIsFlagged() {
            SourceImporter.ImportResult result = draftFrom(session -> {
                capture(session, "POST", "https://shop.internal/api/orders", 201, "page-1",
                        "application/json");
                capture(session, "GET", "https://shop.internal/api/orders/12345", 200, "page-1",
                        "application/json");
            });

            assertThat(result.findings().findings())
                    .extracting(ValidationFinding::code)
                    .isNotEmpty();
        }

        @Test
        @DisplayName("nothing recorded means nothing imported, and no scenarios invented")
        void nothingRecorded() {
            SourceImporter.ImportResult result = IMPORTER.importFrom(
                    RecorderHarRenderer.render(List.of()));

            assertThat(result.scenarios()).isEmpty();
        }
    }

    // ------------------------------------------------------------------ fixtures

    private static SourceImporter.ImportResult draftFrom(java.util.function.Consumer<LiveRecorderSession> recording) {
        LiveRecorderSession session = newSession();
        recording.accept(session);
        return IMPORTER.importFrom(RecorderHarRenderer.render(session.allCaptures()));
    }

    private static void capture(LiveRecorderSession session, String method, String url, int status,
            String journeyRef, String responseType) {

        session.onCapture(new RecorderCapture(method, url,
                Map.of("accept", "application/json"), null, null,
                Map.of(), responseType, "{}", null, status, journeyRef), NOW);
    }

    private static LiveRecorderSession newSession() {
        RecorderSessionPolicy policy = RecorderSessionPolicy
                .forTarget(RecorderOrigin.parse("https://shop.internal"), List.of(ip("10.1.2.3")))
                .withAllowances(RecorderAddressAllowances.strict())
                .build();

        LiveRecorderSession session = new LiveRecorderSession(
                com.perfplatform.domain.identity.RecorderSessionId.generate(),
                com.perfplatform.domain.identity.TeamId.generate(),
                "shop", "performance", "https://shop.internal", "https://shop.internal",
                policy, RecorderViewport.DEFAULT, RecorderBridgeSettings.defaults(), NOW);
        session.markStarting();
        return session;
    }

    private static InetAddress ip(String literal) {
        try {
            return InetAddress.getByName(literal);
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }
}
