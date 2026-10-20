package com.perfplatform.persistence;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * Test-only bootstrap so slice tests in this module have a {@code @SpringBootConfiguration}.
 *
 * <p>{@code platform-persistence} deliberately has no application class of its own — it is a
 * library, and {@code platform-core} is the single composition root. This lives in test sources
 * only and is never packaged, so the module keeps exactly one production entry point.
 */
@SpringBootApplication
public class PersistenceTestApplication {

    /**
     * A fixed clock. Timestamps written by the registry are then deterministic, so a test can
     * assert on them without tolerances and without sleeping.
     */
    @Bean
    public Clock testClock() {
        return Clock.fixed(Instant.parse("2026-07-29T10:15:30Z"), ZoneOffset.UTC);
    }
}
