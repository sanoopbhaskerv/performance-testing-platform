package com.perfplatform.domain.recorder;

import java.util.Objects;

/** The route-guard decision for one attempted recorder destination. */
public record RecorderDecision(boolean allowed, String code, String reason) {

    public RecorderDecision {
        code = Objects.requireNonNull(code, "code");
        reason = Objects.requireNonNull(reason, "reason");
    }

    public static RecorderDecision allow() {
        return new RecorderDecision(true, "recorder.destination.allowed", "Destination is allowed.");
    }

    public static RecorderDecision block(String code, String reason) {
        return new RecorderDecision(false, code, reason);
    }
}
