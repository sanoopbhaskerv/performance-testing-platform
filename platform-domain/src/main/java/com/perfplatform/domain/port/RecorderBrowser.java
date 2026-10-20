package com.perfplatform.domain.port;

import com.perfplatform.domain.identity.RecorderSessionId;
import com.perfplatform.domain.recorder.RecorderInputEvent;
import com.perfplatform.domain.recorder.RecorderSessionPolicy;
import com.perfplatform.domain.recorder.RecorderViewport;

import java.time.Duration;
import java.util.Objects;

/**
 * The CDP bridge, as the control plane sees it.
 *
 * <p>One port, two intended adapters, exactly as CLAUDE.md §15 requires of JMeter: a local browser
 * for development, and a browser inside a recorder Pod for a shared cell. Neither is visible above
 * this interface, so the orchestration layer never knows whether the browser is a child process on
 * a laptop or a Job in another cluster.
 *
 * <p>The interface is narrow on purpose. There is no method that takes a DevTools command, a
 * JavaScript expression or a file path — see {@link com.perfplatform.domain.recorder.RecorderInputKind}
 * for the complete vocabulary a user can reach.
 */
public interface RecorderBrowser {

    /**
     * Whether this deployment can actually start a browser.
     *
     * <p>A session must not be told it is starting when the adapter has no browser to launch; a
     * false here is what keeps the session honestly in {@code PENDING_BROWSER_BRIDGE}.
     */
    boolean isAvailable();

    /** A short description of the adapter, for the console and for audit detail. */
    String describe();

    /**
     * Launches a browser locked to {@code request}'s frozen policy.
     *
     * @throws RecorderBrowserUnavailableException if no browser could be started
     */
    RecorderBrowserHandle open(RecorderBrowserRequest request);

    /**
     * What the adapter needs to start one session.
     *
     * @param policy      the frozen allowlist; the adapter must enforce it at the browser layer
     * @param startUrl    the first navigation, already inside {@code policy}
     * @param idleTimeout how long without input before the browser closes itself
     * @param deadline    hard cap on session length regardless of activity
     */
    record RecorderBrowserRequest(
            RecorderSessionId sessionId,
            RecorderSessionPolicy policy,
            RecorderViewport viewport,
            String startUrl,
            Duration idleTimeout,
            Duration deadline,
            RecorderBrowserListener listener) {

        public RecorderBrowserRequest {
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(policy, "policy");
            Objects.requireNonNull(viewport, "viewport");
            Objects.requireNonNull(listener, "listener");
            if (startUrl == null || startUrl.isBlank()) {
                throw new IllegalArgumentException("A recorder session needs a start URL");
            }
            if (idleTimeout == null || idleTimeout.isNegative() || idleTimeout.isZero()) {
                throw new IllegalArgumentException("Recorder idle timeout must be positive");
            }
            if (deadline == null || deadline.compareTo(idleTimeout) < 0) {
                throw new IllegalArgumentException(
                        "Recorder deadline must be at least the idle timeout");
            }
        }
    }

    /** A running browser. Closing is idempotent and must always be safe to call. */
    interface RecorderBrowserHandle extends AutoCloseable {

        RecorderSessionId sessionId();

        boolean isAlive();

        /**
         * Applies one brokered interaction.
         *
         * @throws IllegalStateException if the browser has already ended
         */
        void dispatch(RecorderInputEvent event);

        @Override
        void close();
    }

    class RecorderBrowserUnavailableException extends RuntimeException {
        public RecorderBrowserUnavailableException(String reason) {
            super(reason);
        }

        public RecorderBrowserUnavailableException(String reason, Throwable cause) {
            super(reason, cause);
        }
    }
}
