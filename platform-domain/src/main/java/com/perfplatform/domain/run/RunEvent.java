package com.perfplatform.domain.run;

import com.perfplatform.domain.identity.TestRunId;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One append-only entry in a run's history.
 *
 * <p>The event log is what makes a run reconstructable after the fact — who asked for it, when it
 * was admitted, why it waited, what stopped it. V1 could answer none of these: it kept a current
 * status and nothing else, so "why did this fail at 02:00" was unanswerable the next morning.
 *
 * <p>Events are never updated or deleted. A correction is a new event, not an edit, because an
 * audit trail that can be rewritten is not an audit trail.
 *
 * <h2>Detail is sanitized by construction</h2>
 * {@link #detail()} holds short operational facts — a shard index, a lease owner, a queue position.
 * It must never carry a request body, an authorization header, a secret value or a raw exception
 * message: run events are read by every member of a team and exported to operational tooling.
 * Callers pass already-safe values; the type deliberately offers no way to attach an arbitrary
 * object, which would invite serializing whatever was at hand.
 *
 * @param sequence      monotonic position in this run's history, assigned by the store; 0 before
 *                      the event is persisted
 * @param runId         the run this event belongs to
 * @param type          what happened
 * @param previousState state before the change, absent for events that are not state changes
 * @param newState      state after the change, absent for events that are not state changes
 * @param actor         who or what caused it — a subject identity, or a component name for
 *                      system-initiated events such as reconciliation
 * @param detail        sanitized key/value context
 * @param occurredAt    UTC instant the event happened
 */
public record RunEvent(
        long sequence,
        TestRunId runId,
        RunEventType type,
        Optional<RunState> previousState,
        Optional<RunState> newState,
        String actor,
        Map<String, String> detail,
        Instant occurredAt) {

    /** Actor recorded for events the platform raises on its own behalf. */
    public static final String SYSTEM_ACTOR = "system:reconciler";

    public RunEvent {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(type, "type");
        previousState = previousState == null ? Optional.empty() : previousState;
        newState = newState == null ? Optional.empty() : newState;
        if (actor == null || actor.isBlank()) {
            throw new IllegalArgumentException("Run event actor must not be null or blank");
        }
        Objects.requireNonNull(occurredAt, "occurredAt");
        detail = detail == null ? Map.of() : Map.copyOf(detail);
    }

    /** A state-change event. */
    public static RunEvent stateChanged(TestRunId runId, RunState from, RunState to,
            String actor, Map<String, String> detail, Instant occurredAt) {
        return new RunEvent(0L, runId, RunEventType.STATE_CHANGED,
                Optional.of(from), Optional.of(to), actor, detail, occurredAt);
    }

    /** An event that records something other than a state change. */
    public static RunEvent of(TestRunId runId, RunEventType type, String actor,
            Map<String, String> detail, Instant occurredAt) {
        return new RunEvent(0L, runId, type, Optional.empty(), Optional.empty(),
                actor, detail, occurredAt);
    }

    /** Convenience for the common single-fact detail map, preserving insertion order. */
    public static Map<String, String> detail(String key, String value) {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put(key, value);
        return detail;
    }

    /** The same event with the sequence number the store assigned. */
    public RunEvent withSequence(long assigned) {
        return new RunEvent(assigned, runId, type, previousState, newState, actor, detail, occurredAt);
    }

    /** Whether this event represents a state change. */
    public boolean isStateChange() {
        return type == RunEventType.STATE_CHANGED;
    }
}
