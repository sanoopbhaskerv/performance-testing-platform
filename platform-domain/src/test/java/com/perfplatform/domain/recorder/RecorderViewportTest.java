package com.perfplatform.domain.recorder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecorderViewportTest {

    @Test
    @DisplayName("a viewport large enough to exhaust a recorder pod is refused")
    void oversizeIsRefused() {
        assertThatThrownBy(() -> new RecorderViewport(16_000, 800))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("width");
    }

    @Test
    @DisplayName("a viewport too small to interact with is refused")
    void undersizeIsRefused() {
        assertThatThrownBy(() -> new RecorderViewport(1_280, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("height");
    }

    @Test
    @DisplayName("clamping accepts a browser-reported size instead of failing the session")
    void clampingIsForwardOnly() {
        assertThat(RecorderViewport.clamped(9_999, 1))
                .isEqualTo(new RecorderViewport(RecorderViewport.MAX_DIMENSION,
                        RecorderViewport.MIN_DIMENSION));
    }

    @Test
    @DisplayName("containment is inclusive of the edge and excludes negatives")
    void containment() {
        RecorderViewport viewport = new RecorderViewport(1_000, 500);

        assertThat(viewport.contains(0, 0)).isTrue();
        assertThat(viewport.contains(1_000, 500)).isTrue();
        assertThat(viewport.contains(1_000.5, 500)).isFalse();
        assertThat(viewport.contains(-1, 10)).isFalse();
    }
}
