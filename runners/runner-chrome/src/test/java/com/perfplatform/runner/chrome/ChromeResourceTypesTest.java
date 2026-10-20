package com.perfplatform.runner.chrome;

import com.perfplatform.domain.recorder.RecorderRequestKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChromeResourceTypesTest {

    private static final String MAIN = "frame-main";

    @Test
    @DisplayName("a document in the main frame is a top-level navigation")
    void mainFrameDocument() {
        assertThat(ChromeResourceTypes.toRequestKind("Document", MAIN, MAIN))
                .isEqualTo(RecorderRequestKind.TOP_LEVEL_NAVIGATION);
    }

    @Test
    @DisplayName("a document in any other frame is an iframe, not a navigation")
    void subFrameDocument() {
        assertThat(ChromeResourceTypes.toRequestKind("Document", "frame-child", MAIN))
                .isEqualTo(RecorderRequestKind.IFRAME);
    }

    @Test
    @DisplayName("the request surfaces the guard cares about are distinguished")
    void surfacesAreMapped() {
        assertThat(ChromeResourceTypes.toRequestKind("XHR", MAIN, MAIN))
                .isEqualTo(RecorderRequestKind.FETCH);
        assertThat(ChromeResourceTypes.toRequestKind("EventSource", MAIN, MAIN))
                .isEqualTo(RecorderRequestKind.EVENT_SOURCE);
        assertThat(ChromeResourceTypes.toRequestKind("WebSocket", MAIN, MAIN))
                .isEqualTo(RecorderRequestKind.WEBSOCKET);
        assertThat(ChromeResourceTypes.toRequestKind("ServiceWorker", MAIN, MAIN))
                .isEqualTo(RecorderRequestKind.WORKER);
    }

    @Test
    @DisplayName("an unknown resource type is still gated, as a subresource")
    void unknownIsStillGated() {
        assertThat(ChromeResourceTypes.toRequestKind("SomethingNew", MAIN, MAIN))
                .isEqualTo(RecorderRequestKind.SUBRESOURCE);
        assertThat(ChromeResourceTypes.toRequestKind(null, null, MAIN))
                .isEqualTo(RecorderRequestKind.SUBRESOURCE);
    }

    @Test
    @DisplayName("static assets are not captured, so a journey is not buried in images and fonts")
    void staticAssetsAreNotCaptured() {
        assertThat(ChromeResourceTypes.isWorthCapturing("Image")).isFalse();
        assertThat(ChromeResourceTypes.isWorthCapturing("Font")).isFalse();
        assertThat(ChromeResourceTypes.isWorthCapturing("Stylesheet")).isFalse();
        assertThat(ChromeResourceTypes.isWorthCapturing("Script")).isFalse();
        assertThat(ChromeResourceTypes.isWorthCapturing("XHR")).isTrue();
        assertThat(ChromeResourceTypes.isWorthCapturing("Document")).isTrue();
    }

    @Test
    @DisplayName("only textual bodies are eligible to be read")
    void textualBodiesOnly() {
        assertThat(ChromeResourceTypes.isTextualContent("application/json")).isTrue();
        assertThat(ChromeResourceTypes.isTextualContent("text/html; charset=utf-8")).isTrue();
        assertThat(ChromeResourceTypes.isTextualContent("application/xml")).isTrue();
        assertThat(ChromeResourceTypes.isTextualContent("image/png")).isFalse();
        assertThat(ChromeResourceTypes.isTextualContent("application/octet-stream")).isFalse();
        assertThat(ChromeResourceTypes.isTextualContent(null)).isFalse();
    }
}
