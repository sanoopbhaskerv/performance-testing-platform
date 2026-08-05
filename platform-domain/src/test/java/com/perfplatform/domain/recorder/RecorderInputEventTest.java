package com.perfplatform.domain.recorder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecorderInputEventTest {

    @Nested
    class Vocabulary {

        @Test
        @DisplayName("the permitted vocabulary contains no way to name a CDP method")
        void noRawProtocolAccess() {
            assertThat(RecorderInputKind.values()).hasSize(10);
            assertThatThrownBy(() -> RecorderInputKind.from("Runtime.evaluate"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> RecorderInputKind.from("Page.captureScreenshot"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class MouseEvents {

        @Test
        @DisplayName("a pointer outside the recorded viewport is refused")
        void pointerMustBeInsideViewport() {
            RecorderInputEvent event = RecorderInputEvent.mouse(RecorderInputKind.MOUSE_PRESSED,
                    5_000, 20, RecorderMouseButton.LEFT, 1, 0);

            assertThatThrownBy(() -> event.requireWithin(RecorderViewport.DEFAULT))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("outside");
        }

        @Test
        @DisplayName("a pointer on the viewport edge is accepted")
        void edgeIsInside() {
            RecorderInputEvent event = RecorderInputEvent.mouse(RecorderInputKind.MOUSE_MOVED,
                    1_280, 800, RecorderMouseButton.NONE, 0, 0);

            assertThat(event.requireWithin(RecorderViewport.DEFAULT)).isSameAs(event);
        }

        @Test
        @DisplayName("non-finite coordinates are refused")
        void coordinatesMustBeFinite() {
            assertThatThrownBy(() -> RecorderInputEvent.mouse(RecorderInputKind.MOUSE_MOVED,
                    Double.NaN, 10, RecorderMouseButton.NONE, 0, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("finite");
        }

        @Test
        @DisplayName("an implausible wheel delta is refused")
        void wheelDeltaIsBounded() {
            assertThatThrownBy(() -> RecorderInputEvent.wheel(10, 10, 0, 999_999, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("deltaY");
        }

        @Test
        @DisplayName("keyboard events ignore the viewport")
        void keyboardIsNotPositional() {
            RecorderInputEvent event = RecorderInputEvent.key(RecorderInputKind.KEY_DOWN,
                    "Enter", "Enter", 0);

            assertThat(event.requireWithin(RecorderViewport.DEFAULT)).isSameAs(event);
        }
    }

    @Nested
    class KeyboardEvents {

        @Test
        @DisplayName("a key event must name a key")
        void keyRequired() {
            assertThatThrownBy(() -> RecorderInputEvent.key(RecorderInputKind.KEY_DOWN, "", "KeyA", 0))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> RecorderInputEvent.key(RecorderInputKind.KEY_DOWN, null, "KeyA", 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("the space bar survives validation")
        void spaceIsAKey() {
            // KeyboardEvent.key for the space bar is literally " ". Rejecting blank keys, which
            // reads like the obvious tightening, silently breaks typing.
            assertThat(RecorderInputEvent.key(RecorderInputKind.KEY_DOWN, " ", "Space", 0).key())
                    .isEqualTo(" ");
        }

        @Test
        @DisplayName("a key name longer than any real key is refused")
        void keyIsBounded() {
            assertThatThrownBy(() -> RecorderInputEvent.key(RecorderInputKind.KEY_DOWN,
                    "x".repeat(64), "KeyA", 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("key");
        }

        @Test
        @DisplayName("typed text is capped so input cannot become an upload channel")
        void textIsBounded() {
            assertThatThrownBy(() -> RecorderInputEvent.text(
                    "a".repeat(RecorderInputEvent.MAX_TEXT_LENGTH + 1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("512");
        }

        @Test
        @DisplayName("modifiers outside the four-bit mask are refused")
        void modifiersAreBounded() {
            assertThatThrownBy(() -> RecorderInputEvent.key(RecorderInputKind.KEY_DOWN,
                    "a", "KeyA", 99))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("bitmask");
        }
    }

    @Nested
    class Navigation {

        @Test
        @DisplayName("a relative navigation is refused before it reaches the route guard")
        void navigationMustBeAbsolute() {
            assertThatThrownBy(() -> RecorderInputEvent.navigate("/admin"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("an absolute navigation parses; the allowlist decision belongs elsewhere")
        void navigationParses() {
            RecorderInputEvent event = RecorderInputEvent.navigate("https://elsewhere.example/x");

            assertThat(event.url()).isEqualTo("https://elsewhere.example/x");
        }
    }

    @Nested
    class AuditSummary {

        @Test
        @DisplayName("typed text never appears in the audit summary")
        void textIsNotAudited() {
            String summary = RecorderInputEvent.text("hunter2-the-real-password").auditSummary();

            assertThat(summary).doesNotContain("hunter2").isEqualTo("TEXT length=25");
        }

        @Test
        @DisplayName("a navigation target is not repeated into the audit summary")
        void navigationUrlIsNotAudited() {
            String summary = RecorderInputEvent
                    .navigate("https://sso.example/callback?code=secret-grant").auditSummary();

            assertThat(summary).doesNotContain("secret-grant").isEqualTo("NAVIGATE");
        }

        @Test
        @DisplayName("a mouse click is summarised by button, not position")
        void mouseSummary() {
            assertThat(RecorderInputEvent.mouse(RecorderInputKind.MOUSE_PRESSED, 12, 34,
                    RecorderMouseButton.LEFT, 1, 0).auditSummary())
                    .isEqualTo("MOUSE_PRESSED button=LEFT");
        }
    }
}
