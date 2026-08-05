package com.perfplatform.runner.chrome;

import com.perfplatform.domain.recorder.RecorderInputEvent;
import com.perfplatform.domain.recorder.RecorderInputKind;
import com.perfplatform.domain.recorder.RecorderMouseButton;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ChromeInputCommandsTest {

    /** The complete set of DevTools methods a user's input can ever become. */
    private static final Set<String> PERMITTED_METHODS = Set.of(
            "Input.dispatchMouseEvent",
            "Input.dispatchKeyEvent",
            "Input.insertText",
            "Page.navigate",
            "Page.reload");

    @ParameterizedTest
    @EnumSource(RecorderInputKind.class)
    @DisplayName("no input maps to a protocol method outside the permitted set")
    void vocabularyIsClosed(RecorderInputKind kind) {
        ChromeInputCommands.translate(sample(kind))
                .ifPresent(call -> assertThat(PERMITTED_METHODS).contains(call.method()));
    }

    @Test
    @DisplayName("a click carries a non-zero click count, or Chrome ignores it")
    void clickCountIsForced() {
        var call = ChromeInputCommands.translate(RecorderInputEvent.mouse(
                RecorderInputKind.MOUSE_PRESSED, 10, 20, RecorderMouseButton.LEFT, 0, 0))
                .orElseThrow();

        assertThat(call.method()).isEqualTo("Input.dispatchMouseEvent");
        assertThat(call.params())
                .containsEntry("type", "mousePressed")
                .containsEntry("button", "left")
                .containsEntry("clickCount", 1)
                .containsEntry("x", 10.0)
                .containsEntry("y", 20.0);
    }

    @Test
    @DisplayName("a wheel event carries its deltas and a move does not")
    void wheelCarriesDeltas() {
        var wheel = ChromeInputCommands.translate(
                RecorderInputEvent.wheel(5, 5, 0, 120, 0)).orElseThrow();
        var move = ChromeInputCommands.translate(RecorderInputEvent.mouse(
                RecorderInputKind.MOUSE_MOVED, 5, 5, RecorderMouseButton.NONE, 0, 0)).orElseThrow();

        assertThat(wheel.params()).containsEntry("type", "mouseWheel").containsEntry("deltaY", 120.0);
        assertThat(move.params()).doesNotContainKey("deltaY");
    }

    @Test
    @DisplayName("a printable key types its character")
    void printableKeyCarriesText() {
        var call = ChromeInputCommands.translate(
                RecorderInputEvent.key(RecorderInputKind.KEY_DOWN, "a", "KeyA", 0)).orElseThrow();

        assertThat(call.params())
                .containsEntry("type", "keyDown")
                .containsEntry("text", "a");
    }

    @Test
    @DisplayName("Enter carries a virtual key code, without which Chrome swallows it")
    void enterCarriesVirtualKeyCode() {
        var call = ChromeInputCommands.translate(
                RecorderInputEvent.key(RecorderInputKind.KEY_DOWN, "Enter", "Enter", 0))
                .orElseThrow();

        assertThat(call.params())
                .containsEntry("type", "rawKeyDown")
                .containsEntry("windowsVirtualKeyCode", 13)
                .doesNotContainKey("text");
    }

    @Test
    @DisplayName("the space bar both types a space and carries its key code")
    void spaceIsBoth() {
        var call = ChromeInputCommands.translate(
                RecorderInputEvent.key(RecorderInputKind.KEY_DOWN, " ", "Space", 0)).orElseThrow();

        assertThat(call.params())
                .containsEntry("text", " ")
                .containsEntry("windowsVirtualKeyCode", 32);
    }

    @Test
    @DisplayName("typed text becomes an insert, not a stream of synthetic key events")
    void textBecomesInsertText() {
        var call = ChromeInputCommands.translate(RecorderInputEvent.text("hello")).orElseThrow();

        assertThat(call.method()).isEqualTo("Input.insertText");
        assertThat(call.params()).containsEntry("text", "hello");
    }

    @Test
    @DisplayName("a navigation becomes Page.navigate and nothing more")
    void navigateIsPlain() {
        var call = ChromeInputCommands.translate(
                RecorderInputEvent.navigate("https://proposal.perf.internal/start")).orElseThrow();

        assertThat(call.method()).isEqualTo("Page.navigate");
        assertThat(call.params()).containsExactly(
                org.assertj.core.api.Assertions.entry("url", "https://proposal.perf.internal/start"));
    }

    @Test
    @DisplayName("going back needs the page's history, so it is not a plain translation")
    void backNeedsHistory() {
        assertThat(ChromeInputCommands.translate(
                RecorderInputEvent.of(RecorderInputKind.NAVIGATE_BACK))).isEmpty();
    }

    @Test
    @DisplayName("no input translates to script evaluation")
    void noScriptEvaluation() {
        for (RecorderInputKind kind : RecorderInputKind.values()) {
            ChromeInputCommands.translate(sample(kind)).ifPresent(call ->
                    assertThat(call.method())
                            .doesNotContain("Runtime")
                            .doesNotContain("evaluate")
                            .doesNotContain("addScript"));
        }
    }

    private static RecorderInputEvent sample(RecorderInputKind kind) {
        return switch (kind) {
            case MOUSE_MOVED, MOUSE_PRESSED, MOUSE_RELEASED ->
                    RecorderInputEvent.mouse(kind, 1, 1, RecorderMouseButton.LEFT, 1, 0);
            case MOUSE_WHEEL -> RecorderInputEvent.wheel(1, 1, 0, 10, 0);
            case KEY_DOWN, KEY_UP -> RecorderInputEvent.key(kind, "a", "KeyA", 0);
            case TEXT -> RecorderInputEvent.text("x");
            case NAVIGATE -> RecorderInputEvent.navigate("https://proposal.perf.internal/");
            case RELOAD, NAVIGATE_BACK -> RecorderInputEvent.of(kind);
        };
    }
}
