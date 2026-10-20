package com.perfplatform.api.v2.dto;

import com.perfplatform.domain.run.RunEvent;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Map;

/**
 * One entry in a run's history.
 *
 * <p>The sequence is part of the contract, not decoration: a client that dropped a connection
 * resumes by asking for events after the last sequence it saw, rather than re-reading a history
 * that can be long for a run lasting hours.
 *
 * @param sequence      position in this run's history
 * @param type          what happened
 * @param previousState state before the change, null for events that are not state changes
 * @param newState      state after the change, null for events that are not state changes
 * @param actor         who or what caused it
 * @param detail        sanitized key/value context
 * @param occurredAt    when it happened
 */
@Schema(description = "One entry in a run's append-only history")
public record RunEventResponse(
        long sequence,
        String type,
        String previousState,
        String newState,
        String actor,
        Map<String, String> detail,
        Instant occurredAt) {

    public static RunEventResponse from(RunEvent event) {
        return new RunEventResponse(
                event.sequence(),
                event.type().name(),
                event.previousState().map(Enum::name).orElse(null),
                event.newState().map(Enum::name).orElse(null),
                event.actor(),
                event.detail(),
                event.occurredAt());
    }
}
