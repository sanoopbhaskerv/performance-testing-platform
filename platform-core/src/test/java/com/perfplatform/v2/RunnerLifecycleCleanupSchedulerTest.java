package com.perfplatform.v2;

import com.perfplatform.runner.kubernetes.EphemeralSecretCleaner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
class RunnerLifecycleCleanupSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-08-03T16:45:00Z");

    @Test
    @DisplayName("cleanup deletes expired runner Secrets using the injected clock")
    void cleanupUsesInjectedClock() {
        FakeCleaner cleaner = new FakeCleaner(2);

        RunnerLifecycleCleanupScheduler scheduler = new RunnerLifecycleCleanupScheduler(
                cleaner, Clock.fixed(NOW, ZoneOffset.UTC), new OrchestrationProperties());

        scheduler.cleanup();

        org.assertj.core.api.Assertions.assertThat(cleaner.lastObservedNow).isEqualTo(NOW);
        org.assertj.core.api.Assertions.assertThat(cleaner.calls).isEqualTo(1);
    }

    @Test
    @DisplayName("cleanup failures are contained so the schedule keeps running")
    void cleanupFailureIsContained() {
        RunnerLifecycleCleanupScheduler scheduler = new RunnerLifecycleCleanupScheduler(
                new FailingCleaner(), Clock.fixed(NOW, ZoneOffset.UTC),
                new OrchestrationProperties());

        assertThatCode(scheduler::cleanup).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the schedule uses the configured lifecycle cleanup interval")
    void scheduleUsesConfiguredInterval() {
        OrchestrationProperties properties = new OrchestrationProperties();
        properties.getLifecycleCleanup().setInterval(Duration.ofMinutes(7));
        RunnerLifecycleCleanupScheduler scheduler = new RunnerLifecycleCleanupScheduler(
                new FakeCleaner(0), Clock.fixed(NOW, ZoneOffset.UTC), properties);
        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();

        scheduler.configureTasks(registrar);

        assertThat(registrar.getFixedDelayTaskList())
                .singleElement()
                .satisfies(task ->
                        assertThat(task.getIntervalDuration()).isEqualTo(Duration.ofMinutes(7)));
    }

    private static final class FakeCleaner implements EphemeralSecretCleaner {
        private final int deleted;
        private Instant lastObservedNow;
        private int calls;

        private FakeCleaner(int deleted) {
            this.deleted = deleted;
        }

        @Override
        public int deleteExpired(Instant now) {
            this.lastObservedNow = now;
            this.calls++;
            return deleted;
        }
    }

    private static final class FailingCleaner implements EphemeralSecretCleaner {
        @Override
        public int deleteExpired(Instant now) {
            throw new IllegalStateException("api unavailable");
        }
    }
}
