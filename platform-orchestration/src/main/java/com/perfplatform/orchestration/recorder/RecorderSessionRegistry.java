package com.perfplatform.orchestration.recorder;

import com.perfplatform.domain.identity.RecorderSessionId;
import com.perfplatform.domain.recorder.RecorderSessionState;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The live recording sessions this control-plane instance owns.
 *
 * <p>Instance-scoped, which is a real constraint worth stating plainly: with more than one API
 * replica, a session's poll and input requests must reach the replica that holds its browser. In
 * the standalone profile there is one replica and the question does not arise. The shared-runner
 * profile answers it differently anyway — the browser lives in a runner cell and the handle becomes
 * a remote one — so building session affinity now would be building it for an architecture that is
 * being replaced.
 *
 * <p>A caller asking for a session it does not own gets the same {@code Optional.empty()} as one
 * asking for a session that never existed. The team check happens above; this class does not
 * authorize.
 */
public final class RecorderSessionRegistry {

    /** Guards against a leaked session slowly filling the heap with frame buffers. */
    private final int maxConcurrentSessions;

    /**
     * How long an ended session stays readable.
     *
     * <p>Forgetting a session the moment it ends turns a client's next poll into a 404, which reads
     * as "no such session" when the truth is "it expired two seconds ago". Keeping it briefly lets
     * the last poll return {@code EXPIRED} or {@code CANCELLED} with a reason, which is both honest
     * and what stops a client retrying something that will never come back. Its browser is already
     * closed and its buffers are what remain, so the cost is small and bounded.
     */
    private final Duration terminalRetention;

    private final Map<RecorderSessionId, LiveRecorderSession> sessions = new ConcurrentHashMap<>();

    public RecorderSessionRegistry(int maxConcurrentSessions, Duration terminalRetention) {
        if (maxConcurrentSessions < 1) {
            throw new IllegalArgumentException("At least one recorder session must be permitted");
        }
        this.maxConcurrentSessions = maxConcurrentSessions;
        this.terminalRetention = terminalRetention == null ? Duration.ofMinutes(10)
                : terminalRetention;
    }

    public RecorderSessionRegistry(int maxConcurrentSessions) {
        this(maxConcurrentSessions, Duration.ofMinutes(10));
    }

    public static RecorderSessionRegistry withDefaults() {
        return new RecorderSessionRegistry(10);
    }

    /**
     * @throws CapacityExceededException if too many sessions are already live
     */
    public void register(LiveRecorderSession session) {
        Objects.requireNonNull(session, "session");
        synchronized (this) {
            if (activeCount() >= maxConcurrentSessions) {
                throw new CapacityExceededException(maxConcurrentSessions);
            }
            sessions.put(session.id(), session);
        }
    }

    public Optional<LiveRecorderSession> find(RecorderSessionId id) {
        return Optional.ofNullable(sessions.get(id));
    }

    public Collection<LiveRecorderSession> all() {
        return List.copyOf(sessions.values());
    }

    public long activeCount() {
        return sessions.values().stream().filter(session -> !session.state().isTerminal()).count();
    }

    /**
     * Drops sessions that ended long enough ago that nobody is still reading them.
     *
     * @return the sessions forgotten by this sweep
     */
    public List<LiveRecorderSession> evictTerminal(Instant now) {
        List<LiveRecorderSession> evicted = new ArrayList<>();
        sessions.values().removeIf(session -> {
            if (!session.state().isTerminal()) {
                return false;
            }
            Instant endedAt = session.terminatedAt().orElse(Instant.EPOCH);
            if (Duration.between(endedAt, now).compareTo(terminalRetention) < 0) {
                return false;
            }
            evicted.add(session);
            return true;
        });
        return evicted;
    }

    /**
     * Ends sessions that have gone quiet or run out of time.
     *
     * @return the sessions this sweep ended, for the caller to audit
     */
    public List<LiveRecorderSession> expireStale(Instant now) {
        List<LiveRecorderSession> expired = new ArrayList<>();
        for (LiveRecorderSession session : sessions.values()) {
            if (session.isPastDeadline(now)) {
                session.terminate(RecorderSessionState.EXPIRED,
                        "recorder.session.deadline",
                        "The recording session reached its maximum length.", now);
                expired.add(session);
            } else if (session.isIdle(now)) {
                session.terminate(RecorderSessionState.EXPIRED,
                        "recorder.session.idle",
                        "The recording session was idle and closed itself.", now);
                expired.add(session);
            }
        }
        return expired;
    }

    public static class CapacityExceededException extends RuntimeException {
        public CapacityExceededException(int limit) {
            super("This deployment already has " + limit + " recording sessions open. "
                    + "Close one before starting another.");
        }
    }
}
