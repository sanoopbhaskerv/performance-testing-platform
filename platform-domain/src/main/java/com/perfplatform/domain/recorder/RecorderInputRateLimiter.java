package com.perfplatform.domain.recorder;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Token bucket over one session's brokered input.
 *
 * <p>Required by the recorder security review (§4.4). Without it, an authenticated caller holding
 * one session can drive the browser as fast as it will accept commands, which is a request generator
 * pointed at an internal target that never passed through admission control. The bucket allows the
 * bursts real typing and dragging produce while capping the sustained rate.
 *
 * <p>Deliberately not a Spring or Guava rate limiter: the domain module takes no such dependency,
 * and taking {@code now} as a parameter is what makes the behaviour testable without sleeping.
 */
public final class RecorderInputRateLimiter {

    private final int burst;
    private final double perSecond;

    private double tokens;
    private Instant updatedAt;

    public RecorderInputRateLimiter(int burst, double perSecond, Instant startedAt) {
        if (burst < 1) {
            throw new IllegalArgumentException("Recorder input burst must be at least 1");
        }
        if (perSecond <= 0) {
            throw new IllegalArgumentException("Recorder input rate must be positive");
        }
        this.burst = burst;
        this.perSecond = perSecond;
        this.tokens = burst;
        this.updatedAt = Objects.requireNonNull(startedAt, "startedAt");
    }

    /** Enough for continuous typing and pointer drags, far short of a load generator. */
    public static RecorderInputRateLimiter standard(Instant startedAt) {
        return new RecorderInputRateLimiter(60, 30, startedAt);
    }

    /**
     * @return true if the event may proceed, false if the caller exceeded its budget
     */
    public synchronized boolean tryAcquire(Instant now) {
        refill(now);
        if (tokens < 1) {
            return false;
        }
        tokens -= 1;
        return true;
    }

    public synchronized double availableTokens() {
        return tokens;
    }

    private void refill(Instant now) {
        if (now.isAfter(updatedAt)) {
            double elapsedSeconds = Duration.between(updatedAt, now).toNanos() / 1_000_000_000d;
            tokens = Math.min(burst, tokens + elapsedSeconds * perSecond);
            updatedAt = now;
        }
    }
}
