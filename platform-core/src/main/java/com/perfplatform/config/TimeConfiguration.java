package com.perfplatform.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Supplies the platform's clock.
 *
 * <p>Injected rather than read through {@code Instant.now()} so that time-dependent behaviour —
 * version creation timestamps now, and lease expiry, heartbeat staleness and run duration
 * ceilings in later increments — can be tested deterministically instead of with sleeps.
 *
 * <p>UTC always. Persisted timestamps are UTC {@code Instant}s, and a control plane whose
 * replicas disagree about the local zone would order run events inconsistently.
 */
@Configuration
public class TimeConfiguration {

    @Bean
    public Clock platformClock() {
        return Clock.systemUTC();
    }
}
