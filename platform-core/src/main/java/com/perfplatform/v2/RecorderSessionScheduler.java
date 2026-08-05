package com.perfplatform.v2;

import com.perfplatform.orchestration.recorder.ManageRecordingsUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Closes recording browsers that nobody is watching.
 *
 * <p>Reads and inputs already expire a stale session, which covers the user who walks away with the
 * console open. It does not cover the one who closes the tab: that session stops being polled, so
 * nothing would ever notice it, and an unattended browser sitting inside the runner network with a
 * live corporate session in it is exactly what the idle timeout exists to prevent
 * (recorder security review §4.4).
 *
 * <p>Registered in code rather than with {@code @Scheduled(fixedDelayString)} for the reason
 * {@link ReconciliationScheduler} gives: the interval is an already-parsed {@link Duration}, so the
 * configuration reads {@code 30s} like every other duration in the file.
 */
@Component
public class RecorderSessionScheduler implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(RecorderSessionScheduler.class);

    private final ManageRecordingsUseCase recordings;
    private final Duration interval;

    public RecorderSessionScheduler(ManageRecordingsUseCase recordings,
            OrchestrationProperties properties) {
        this.recordings = recordings;
        this.interval = properties.getRecorder().getSweepInterval();
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(this::sweep, interval);
    }

    public void sweep() {
        try {
            int expired = recordings.sweep();
            if (expired > 0) {
                log.info("Recorder sweep closed {} idle or expired session(s)", expired);
            }
        } catch (RuntimeException e) {
            // Never propagate: an exception out of a scheduled task cancels the schedule, and a
            // sweep that stops running leaves browsers open indefinitely.
            log.error("Recorder sweep failed", e);
        }
    }
}
