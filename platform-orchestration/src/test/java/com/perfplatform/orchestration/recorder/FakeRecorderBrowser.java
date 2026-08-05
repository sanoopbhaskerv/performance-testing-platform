package com.perfplatform.orchestration.recorder;

import com.perfplatform.domain.identity.RecorderSessionId;
import com.perfplatform.domain.port.RecorderBrowser;
import com.perfplatform.domain.port.RecorderBrowserListener;
import com.perfplatform.domain.recorder.RecorderInputEvent;
import com.perfplatform.domain.recorder.RecorderViewport;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A recorder browser that records what it was asked to do instead of launching Chrome.
 *
 * <p>Lets the whole session lifecycle — start, stream, input, block, capture, stop — be tested
 * deterministically, and lets a test push a frame or a blocked destination at the moment it wants
 * one rather than waiting for a real page to do it.
 */
final class FakeRecorderBrowser implements RecorderBrowser {

    private final boolean available;
    private final RuntimeException failOnOpen;

    final List<RecorderInputEvent> dispatched = new ArrayList<>();
    RecorderBrowserRequest lastRequest;
    FakeHandle lastHandle;

    FakeRecorderBrowser() {
        this(true, null);
    }

    private FakeRecorderBrowser(boolean available, RuntimeException failOnOpen) {
        this.available = available;
        this.failOnOpen = failOnOpen;
    }

    static FakeRecorderBrowser unavailable() {
        return new FakeRecorderBrowser(false, null);
    }

    static FakeRecorderBrowser failingToStart(String message) {
        return new FakeRecorderBrowser(true, new RecorderBrowserUnavailableException(message));
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public String describe() {
        return "fake";
    }

    @Override
    public RecorderBrowserHandle open(RecorderBrowserRequest request) {
        this.lastRequest = request;
        if (failOnOpen != null) {
            throw failOnOpen;
        }
        lastHandle = new FakeHandle(request.sessionId(), request.listener(), request.viewport());
        return lastHandle;
    }

    final class FakeHandle implements RecorderBrowserHandle {
        private final RecorderSessionId sessionId;
        private final RecorderBrowserListener listener;
        private final RecorderViewport viewport;
        private boolean alive = true;
        int closeCount;

        private FakeHandle(RecorderSessionId sessionId, RecorderBrowserListener listener,
                RecorderViewport viewport) {
            this.sessionId = sessionId;
            this.listener = listener;
            this.viewport = viewport;
        }

        RecorderBrowserListener listener() {
            return listener;
        }

        void pushFrame(String image, Instant at) {
            listener.onFrame(image, viewport, at);
        }

        @Override
        public RecorderSessionId sessionId() {
            return sessionId;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }

        @Override
        public void dispatch(RecorderInputEvent event) {
            if (!alive) {
                throw new IllegalStateException("closed");
            }
            dispatched.add(event);
        }

        @Override
        public void close() {
            closeCount++;
            alive = false;
        }
    }
}
