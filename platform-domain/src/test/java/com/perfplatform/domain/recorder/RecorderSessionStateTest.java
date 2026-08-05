package com.perfplatform.domain.recorder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecorderSessionStateTest {

    @Test
    @DisplayName("a session walks pending to closed through the bridge")
    void happyPath() {
        RecorderSessionState state = RecorderSessionState.PENDING_BROWSER_BRIDGE
                .transitionTo(RecorderSessionState.STARTING)
                .transitionTo(RecorderSessionState.LIVE)
                .transitionTo(RecorderSessionState.CLOSING)
                .transitionTo(RecorderSessionState.CLOSED);

        assertThat(state).isEqualTo(RecorderSessionState.CLOSED);
        assertThat(state.isTerminal()).isTrue();
    }

    @Test
    @DisplayName("a session cannot go live without starting")
    void noSkippingStart() {
        assertThatThrownBy(() -> RecorderSessionState.PENDING_BROWSER_BRIDGE
                .transitionTo(RecorderSessionState.LIVE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PENDING_BROWSER_BRIDGE");
    }

    @Test
    @DisplayName("only a live session accepts input")
    void onlyLiveAcceptsInput() {
        for (RecorderSessionState state : RecorderSessionState.values()) {
            assertThat(state.acceptsInput()).isEqualTo(state == RecorderSessionState.LIVE);
        }
    }

    @ParameterizedTest
    @EnumSource(value = RecorderSessionState.class,
            names = {"CLOSED", "FAILED", "CANCELLED", "EXPIRED"})
    @DisplayName("terminal states never transition again")
    void terminalIsFinal(RecorderSessionState terminal) {
        assertThat(terminal.isTerminal()).isTrue();
        for (RecorderSessionState next : RecorderSessionState.values()) {
            assertThat(terminal.canTransitionTo(next)).isFalse();
        }
    }

    @Test
    @DisplayName("a session can be cancelled or expire before the browser exists")
    void cancellableWhilePending() {
        assertThat(RecorderSessionState.PENDING_BROWSER_BRIDGE
                .canTransitionTo(RecorderSessionState.CANCELLED)).isTrue();
        assertThat(RecorderSessionState.PENDING_BROWSER_BRIDGE
                .canTransitionTo(RecorderSessionState.EXPIRED)).isTrue();
    }

    @Test
    @DisplayName("a closing session cannot be cancelled, only finish or fail")
    void closingIsCommitted() {
        assertThat(RecorderSessionState.CLOSING.canTransitionTo(RecorderSessionState.CLOSED)).isTrue();
        assertThat(RecorderSessionState.CLOSING.canTransitionTo(RecorderSessionState.FAILED)).isTrue();
        assertThat(RecorderSessionState.CLOSING.canTransitionTo(RecorderSessionState.CANCELLED)).isFalse();
    }
}
