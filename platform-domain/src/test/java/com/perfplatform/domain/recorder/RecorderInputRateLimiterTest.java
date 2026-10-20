package com.perfplatform.domain.recorder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecorderInputRateLimiterTest {

    private static final Instant T0 = Instant.parse("2026-08-05T10:00:00Z");

    @Test
    @DisplayName("a burst of typing passes")
    void burstPasses() {
        RecorderInputRateLimiter limiter = new RecorderInputRateLimiter(10, 5, T0);

        for (int i = 0; i < 10; i++) {
            assertThat(limiter.tryAcquire(T0)).as("event %d", i).isTrue();
        }
    }

    @Test
    @DisplayName("input beyond the burst is refused until the bucket refills")
    void sustainedFloodIsRefused() {
        RecorderInputRateLimiter limiter = new RecorderInputRateLimiter(10, 5, T0);
        for (int i = 0; i < 10; i++) {
            limiter.tryAcquire(T0);
        }

        assertThat(limiter.tryAcquire(T0)).isFalse();
        assertThat(limiter.tryAcquire(T0.plus(Duration.ofMillis(199)))).isFalse();
        assertThat(limiter.tryAcquire(T0.plus(Duration.ofMillis(201)))).isTrue();
    }

    @Test
    @DisplayName("the bucket refills to the burst and no further")
    void refillIsCapped() {
        RecorderInputRateLimiter limiter = new RecorderInputRateLimiter(10, 5, T0);
        limiter.tryAcquire(T0);

        assertThat(limiter.tryAcquire(T0.plus(Duration.ofHours(1)))).isTrue();
        assertThat(limiter.availableTokens()).isEqualTo(9);
    }

    @Test
    @DisplayName("the standard bucket allows continuous typing but not a load generator")
    void standardBucket() {
        RecorderInputRateLimiter limiter = RecorderInputRateLimiter.standard(T0);
        for (int i = 0; i < 60; i++) {
            assertThat(limiter.tryAcquire(T0)).isTrue();
        }

        assertThat(limiter.tryAcquire(T0)).isFalse();
        assertThat(limiter.tryAcquire(T0.plusSeconds(1))).isTrue();
    }

    @Test
    @DisplayName("a non-positive configuration is rejected at construction")
    void configurationIsValidated() {
        assertThatThrownBy(() -> new RecorderInputRateLimiter(0, 5, T0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RecorderInputRateLimiter(10, 0, T0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
