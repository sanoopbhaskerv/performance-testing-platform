package com.perfplatform.v2;

import com.perfplatform.runner.kubernetes.EphemeralSecretCleaner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

/**
 * Sweeps runner-owned ephemeral Kubernetes objects after their explicit lifecycle expires.
 *
 * <p>The execution provider still deletes Secrets on the normal paths: terminal observation,
 * cancellation, missing Jobs and UID mismatches. This scheduler exists for the gap that those paths
 * cannot close alone — the control plane dies before observing the Job again. It is registered only
 * when the Kubernetes runner-cell cleaner bean exists.
 */
@Component
@ConditionalOnBean(EphemeralSecretCleaner.class)
@ConditionalOnProperty(prefix = "platform.orchestration.lifecycle-cleanup", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class RunnerLifecycleCleanupScheduler implements SchedulingConfigurer {

    private static final Logger log =
            LoggerFactory.getLogger(RunnerLifecycleCleanupScheduler.class);

    private final EphemeralSecretCleaner cleaner;
    private final Clock clock;
    private final Duration interval;

    public RunnerLifecycleCleanupScheduler(EphemeralSecretCleaner cleaner, Clock clock,
            OrchestrationProperties properties) {
        this.cleaner = cleaner;
        this.clock = clock;
        this.interval = properties.getLifecycleCleanup().getInterval();
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        log.info("Runner lifecycle cleanup enabled: every {}", interval);
        registrar.addFixedDelayTask(this::cleanup, interval);
    }

    public void cleanup() {
        try {
            int deleted = cleaner.deleteExpired(clock.instant());
            if (deleted > 0) {
                log.info("Runner lifecycle cleanup deleted {} expired Secret(s)", deleted);
            }
        } catch (RuntimeException e) {
            // Never propagate from a scheduled task. A failed cleanup pass is retried; a cancelled
            // schedule would leave expired session material behind until a human noticed.
            log.error("Runner lifecycle cleanup pass failed", e);
        }
    }
}
