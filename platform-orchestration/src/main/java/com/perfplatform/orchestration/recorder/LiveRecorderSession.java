package com.perfplatform.orchestration.recorder;

import com.perfplatform.domain.identity.RecorderSessionId;
import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.port.RecorderBrowser.RecorderBrowserHandle;
import com.perfplatform.domain.port.RecorderBrowserListener;
import com.perfplatform.domain.recorder.RecorderBlockedDestination;
import com.perfplatform.domain.recorder.RecorderCapture;
import com.perfplatform.domain.recorder.RecorderCaptureSanitizer;
import com.perfplatform.domain.recorder.RecorderDecision;
import com.perfplatform.domain.recorder.RecorderInputEvent;
import com.perfplatform.domain.recorder.RecorderInputRateLimiter;
import com.perfplatform.domain.recorder.RecorderRequestKind;
import com.perfplatform.domain.recorder.RecorderSanitizedCapture;
import com.perfplatform.domain.recorder.RecorderScreencastFrame;
import com.perfplatform.domain.recorder.RecorderSessionPolicy;
import com.perfplatform.domain.recorder.RecorderSessionState;
import com.perfplatform.domain.recorder.RecorderViewport;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * One recording session's live, in-memory state.
 *
 * <p>Deliberately <b>not</b> durable. CLAUDE.md §4 makes PostgreSQL the source of truth for runs
 * because a run outlives the control plane that started it; a recording session does not. It is a
 * person sitting in front of a browser, and if the control plane restarts the browser is gone and
 * the session is genuinely over. What <em>is</em> durable is the audit trail — every
 * security-relevant thing this session did survives in {@code recorder_audit_events}, which is what
 * the security review actually requires. Screenshots and captured bodies are held here and dropped
 * with the session (review §5).
 *
 * <p>The session is its own {@link RecorderBrowserListener}. Callbacks arrive on the adapter's
 * threads, so every one of them does the least possible work: append to a bounded buffer and
 * return. Audit writes are queued and drained later by a request thread in
 * {@link #drainPendingAudit()} rather than performed inline, because a database write inside the
 * request-interception callback would stall the browser mid-navigation.
 */
public final class LiveRecorderSession implements RecorderBrowserListener {

    private final RecorderSessionId id;
    private final TeamId teamId;
    private final String application;
    private final String environment;
    private final String targetOrigin;
    private final String startUrl;
    private final RecorderSessionPolicy policy;
    private final RecorderViewport viewport;
    private final RecorderBridgeSettings settings;
    private final RecorderInputRateLimiter inputLimiter;
    private final Instant startedAt;

    private final Deque<RecorderScreencastFrame> frames = new ArrayDeque<>();
    private final Deque<RecorderBlockedDestination> blocked = new ArrayDeque<>();
    private final Deque<CapturedExchange> captures = new ArrayDeque<>();
    private final Deque<RecorderBlockedDestination> pendingAudit = new ArrayDeque<>();
    private final Set<String> auditedBlocks = new HashSet<>();

    private RecorderSessionState state = RecorderSessionState.PENDING_BROWSER_BRIDGE;
    private RecorderBrowserHandle handle;
    private Instant lastActivityAt;
    private String closeCode;
    private String closeReason;
    private long frameSequence;
    private long blockedSequence;
    private long captureSequence;
    private long droppedFrames;
    private long droppedCaptures;
    private Instant terminatedAt;

    public LiveRecorderSession(RecorderSessionId id, TeamId teamId, String application,
            String environment, String targetOrigin, String startUrl, RecorderSessionPolicy policy,
            RecorderViewport viewport, RecorderBridgeSettings settings, Instant startedAt) {

        this.id = Objects.requireNonNull(id, "id");
        this.teamId = Objects.requireNonNull(teamId, "teamId");
        this.application = Objects.requireNonNull(application, "application");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.targetOrigin = Objects.requireNonNull(targetOrigin, "targetOrigin");
        this.startUrl = Objects.requireNonNull(startUrl, "startUrl");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.viewport = Objects.requireNonNull(viewport, "viewport");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
        this.lastActivityAt = startedAt;
        this.inputLimiter = RecorderInputRateLimiter.standard(startedAt);
    }

    /** One sanitized exchange, numbered so a client can resume where it left off. */
    public record CapturedExchange(long sequence, RecorderSanitizedCapture capture,
            Instant capturedAt) {
    }

    // ---------------------------------------------------------------- identity

    public RecorderSessionId id() {
        return id;
    }

    public TeamId teamId() {
        return teamId;
    }

    public String application() {
        return application;
    }

    public String environment() {
        return environment;
    }

    public String targetOrigin() {
        return targetOrigin;
    }

    public String startUrl() {
        return startUrl;
    }

    public RecorderSessionPolicy policy() {
        return policy;
    }

    public RecorderViewport viewport() {
        return viewport;
    }

    public Instant startedAt() {
        return startedAt;
    }

    // ------------------------------------------------------------------- state

    public synchronized RecorderSessionState state() {
        return state;
    }

    public synchronized Optional<String> closeCode() {
        return Optional.ofNullable(closeCode);
    }

    public synchronized Optional<String> closeReason() {
        return Optional.ofNullable(closeReason);
    }

    public synchronized void markStarting() {
        state = state.transitionTo(RecorderSessionState.STARTING);
    }

    public synchronized void markLive(RecorderBrowserHandle browserHandle, Instant now) {
        this.handle = Objects.requireNonNull(browserHandle, "browserHandle");
        this.state = state.transitionTo(RecorderSessionState.LIVE);
        this.lastActivityAt = now;
    }

    /**
     * Moves the session to a terminal state, closing the browser if one is attached.
     *
     * <p>Idempotent: a session that is already terminal keeps the first reason it ended with, so a
     * user cancel that races the browser's own crash does not overwrite the crash.
     */
    public void terminate(RecorderSessionState terminal, String code, String reason, Instant now) {
        if (!terminal.isTerminal()) {
            throw new IllegalArgumentException(terminal + " is not a terminal recorder state");
        }
        RecorderBrowserHandle attached = markTerminated(terminal, code, reason, now);
        if (attached == null) {
            return;
        }
        // Closed outside the lock on purpose. An adapter's close() joins the threads that deliver
        // frames and captures, and those threads take this same lock to append to their buffers.
        // Closing while holding it deadlocks the session against its own browser.
        try {
            attached.close();
        } catch (RuntimeException e) {
            // The browser is going away regardless; a messy close must not prevent the session
            // from having reached a terminal state.
            recordCloseDetail(e.getMessage());
        }
    }

    private synchronized RecorderBrowserHandle markTerminated(RecorderSessionState terminal,
            String code, String reason, Instant now) {

        if (state.isTerminal()) {
            return null;
        }
        RecorderBrowserHandle attached = handle;
        handle = null;
        state = resolveTerminal(terminal);
        closeCode = code;
        closeReason = reason;
        lastActivityAt = now;
        terminatedAt = now;
        // A client waiting for the next frame must not sit there until its timeout when the answer
        // is "this session is over".
        notifyAll();
        return attached;
    }

    private synchronized void recordCloseDetail(String detail) {
        closeReason = closeReason + " (browser close reported: " + detail + ")";
    }

    /**
     * Maps a requested ending onto a transition the state machine actually permits.
     *
     * <p>{@code CLOSED} is reachable only through {@code CLOSING}, and only from {@code LIVE}. A
     * session stopped before its browser existed did not close — it was cancelled before there was
     * anything to close, and saying so is more useful than recording a spurious failure.
     */
    private RecorderSessionState resolveTerminal(RecorderSessionState requested) {
        if (requested == RecorderSessionState.CLOSED) {
            if (state.canTransitionTo(RecorderSessionState.CLOSING)) {
                return RecorderSessionState.CLOSING.transitionTo(RecorderSessionState.CLOSED);
            }
            return state.canTransitionTo(RecorderSessionState.CANCELLED)
                    ? RecorderSessionState.CANCELLED
                    : RecorderSessionState.FAILED;
        }
        return state.canTransitionTo(requested) ? requested : RecorderSessionState.FAILED;
    }

    /**
     * When this session ended, if it has.
     *
     * <p>Read by the registry to decide when it is safe to forget: a client that was polling one
     * moment must not be told the session never existed the next.
     */
    public synchronized Optional<Instant> terminatedAt() {
        return Optional.ofNullable(terminatedAt);
    }

    public synchronized boolean isIdle(Instant now) {
        return !state.isTerminal()
                && Duration.between(lastActivityAt, now).compareTo(settings.idleTimeout()) > 0;
    }

    public synchronized boolean isPastDeadline(Instant now) {
        return !state.isTerminal()
                && Duration.between(startedAt, now).compareTo(settings.deadline()) > 0;
    }

    // ------------------------------------------------------------------- input

    /**
     * Applies one brokered event to the browser.
     *
     * @return the refusal reason, or empty when the event was dispatched
     */
    public synchronized Optional<String> dispatch(RecorderInputEvent event, Instant now) {
        if (!state.acceptsInput() || handle == null) {
            return Optional.of("This recording session is " + state + " and accepts no input.");
        }
        if (!inputLimiter.tryAcquire(now)) {
            return Optional.of("Too many recorder input events; slow down.");
        }
        event.requireWithin(viewport);
        handle.dispatch(event);
        lastActivityAt = now;
        return Optional.empty();
    }

    // -------------------------------------------------------- listener callbacks

    @Override
    public void onFrame(String imageBase64, RecorderViewport frameViewport, Instant capturedAt) {
        synchronized (this) {
            if (state.isTerminal()) {
                return;
            }
            frames.addLast(new RecorderScreencastFrame(++frameSequence, imageBase64,
                    frameViewport == null ? viewport : frameViewport, capturedAt));
            while (frames.size() > settings.frameBufferSize()) {
                frames.removeFirst();
                droppedFrames++;
            }
            notifyAll();
        }
    }

    @Override
    public void onBlocked(String destination, RecorderRequestKind kind, RecorderDecision decision,
            Instant blockedAt) {
        synchronized (this) {
            if (state.isTerminal()) {
                return;
            }
            RecorderBlockedDestination event = RecorderBlockedDestination.from(
                    ++blockedSequence, destination, kind, decision, blockedAt);
            blocked.addLast(event);
            trim(blocked);
            notifyAll();
            // One audit row per distinct origin-and-reason. A page that retries a blocked asset
            // sixty times is one security fact, not sixty.
            if (auditedBlocks.add(decision.code() + '|' + originOf(destination))) {
                pendingAudit.addLast(event);
            }
        }
    }

    @Override
    public void onCapture(RecorderCapture capture, Instant capturedAt) {
        RecorderSanitizedCapture sanitized;
        try {
            sanitized = RecorderCaptureSanitizer.sanitize(capture);
        } catch (RuntimeException e) {
            // A capture that cannot be sanitized is discarded rather than stored raw.
            return;
        }
        synchronized (this) {
            if (state.isTerminal()) {
                return;
            }
            captures.addLast(new CapturedExchange(++captureSequence, sanitized, capturedAt));
            while (captures.size() > settings.eventBufferSize()) {
                captures.removeFirst();
                droppedCaptures++;
            }
            notifyAll();
        }
    }

    @Override
    public void onClosed(String code, String reason, Instant closedAt) {
        boolean failed = code != null && code.startsWith("recorder.browser.failed");
        terminate(failed ? RecorderSessionState.FAILED : RecorderSessionState.CLOSED,
                code, reason, closedAt);
    }

    // ------------------------------------------------------------------ reading

    /**
     * Waits until there is something the client has not seen, or the session ends, or time runs out.
     *
     * <p>This is what makes a live view affordable. Chrome emits a screencast frame only when the
     * page <em>repaints</em>, so a person reading a static page generates no frames at all — and a
     * client polling on a fixed interval then spends its entire session asking for nothing. At the
     * 120ms this used to poll at, a five-minute recording was two thousand requests, almost all of
     * them empty, which reads as a broken loop and is one.
     *
     * <p>Waiting here instead collapses that to roughly one request per thing that actually
     * happened. The timeout is the caller's, and is bounded above by the API layer: a wait that
     * never returns is a connection a client cannot cancel cleanly.
     *
     * @return true if there is new material, false if the wait timed out
     */
    public synchronized boolean awaitUpdate(long afterFrame, long afterBlocked, long afterCapture,
            Duration timeout) {

        long remainingNanos = timeout == null ? 0 : timeout.toNanos();
        while (remainingNanos > 0 && !state.isTerminal()
                && !hasNewerThan(afterFrame, afterBlocked, afterCapture)) {
            long startedAt = System.nanoTime();
            try {
                // wait() releases this monitor, which is the whole point: the browser's callback
                // threads need it to deliver the very frame this is waiting for.
                wait(remainingNanos / 1_000_000L, (int) (remainingNanos % 1_000_000L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            remainingNanos -= System.nanoTime() - startedAt;
        }
        return state.isTerminal() || hasNewerThan(afterFrame, afterBlocked, afterCapture);
    }

    private boolean hasNewerThan(long afterFrame, long afterBlocked, long afterCapture) {
        return frameSequence > afterFrame
                || blockedSequence > afterBlocked
                || captureSequence > afterCapture;
    }

    /** The newest frame after {@code afterSequence}, or empty when the client is current. */
    public synchronized Optional<RecorderScreencastFrame> frameAfter(long afterSequence) {
        return frames.stream()
                .filter(frame -> frame.sequence() > afterSequence)
                .reduce((first, second) -> second);
    }

    public synchronized List<RecorderBlockedDestination> blockedAfter(long afterSequence, int limit) {
        return blocked.stream()
                .filter(event -> event.sequence() > afterSequence)
                .limit(Math.max(1, limit))
                .toList();
    }

    public synchronized List<CapturedExchange> capturesAfter(long afterSequence, int limit) {
        return captures.stream()
                .filter(event -> event.sequence() > afterSequence)
                .limit(Math.max(1, limit))
                .toList();
    }

    public synchronized long capturedCount() {
        return captureSequence;
    }

    public synchronized long blockedCount() {
        return blockedSequence;
    }

    public synchronized long droppedFrames() {
        return droppedFrames;
    }

    /**
     * How many captures fell out of the buffer.
     *
     * <p>The oldest go first, which is the worst end to lose: a recording's first requests are its
     * sign-in. A draft built from a truncated recording must say so rather than quietly omitting the
     * journey the user cared most about.
     */
    public synchronized long droppedCaptures() {
        return droppedCaptures;
    }

    /** Everything the session still holds, oldest first, for building a draft. */
    public synchronized List<CapturedExchange> allCaptures() {
        return List.copyOf(captures);
    }

    /** Hands back the blocked destinations that still need an audit row, and forgets them. */
    public synchronized List<RecorderBlockedDestination> drainPendingAudit() {
        if (pendingAudit.isEmpty()) {
            return List.of();
        }
        List<RecorderBlockedDestination> drained = new ArrayList<>(pendingAudit);
        pendingAudit.clear();
        return drained;
    }

    private void trim(Deque<?> buffer) {
        while (buffer.size() > settings.eventBufferSize()) {
            buffer.removeFirst();
        }
    }

    private static String originOf(String destination) {
        try {
            java.net.URI uri = java.net.URI.create(destination);
            return uri.getScheme() + "://" + uri.getHost()
                    + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
        } catch (RuntimeException e) {
            return destination;
        }
    }
}
