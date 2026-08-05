package com.perfplatform.v2;

import com.perfplatform.domain.recorder.RecorderSessionState;
import com.perfplatform.orchestration.recorder.LiveRecorderSession;
import com.perfplatform.orchestration.recorder.RecorderSessionRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Closes every open recording browser when the control plane shuts down.
 *
 * <p>Recorder sessions are in-memory and instance-scoped, so a restart already ends them logically.
 * The browsers are not in memory: they are child processes, and without this they survive the JVM
 * that started them — a headless Chrome holding whatever the user had signed into, with nothing
 * left running that knows it exists.
 *
 * <p>This covers an orderly shutdown. It cannot cover {@code kill -9}, which is why the adapter also
 * arms its own deadline inside the browser: between the two, an abandoned browser lives until its
 * session deadline rather than forever.
 */
@Component
public class RecorderSessionShutdown {

    private static final Logger log = LoggerFactory.getLogger(RecorderSessionShutdown.class);

    private final RecorderSessionRegistry registry;
    private final Clock clock;

    public RecorderSessionShutdown(RecorderSessionRegistry registry, Clock clock) {
        this.registry = registry;
        this.clock = clock;
    }

    @PreDestroy
    public void closeOpenSessions() {
        int closed = 0;
        for (LiveRecorderSession session : registry.all()) {
            if (session.state().isTerminal()) {
                continue;
            }
            try {
                session.terminate(RecorderSessionState.CANCELLED, "recorder.session.shutdown",
                        "The control plane shut down.", clock.instant());
                closed++;
            } catch (RuntimeException e) {
                // Best effort by definition: the JVM is going away, and one browser that will not
                // close must not stop the others from being closed.
                log.warn("Could not close recorder session {} during shutdown", session.id(), e);
            }
        }
        if (closed > 0) {
            log.info("Closed {} recording browser(s) during shutdown", closed);
        }
    }
}
