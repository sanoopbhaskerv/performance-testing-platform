package com.perfplatform.domain.recorder;

import com.perfplatform.domain.identity.RecorderSessionId;
import com.perfplatform.domain.identity.TeamId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RecorderAuditEventTest {

    @Test
    @DisplayName("audit detail is flattened and bounded before persistence")
    void detailIsSanitized() {
        RecorderAuditEvent event = RecorderAuditEvent.of(
                RecorderSessionId.generate(),
                TeamId.generate(),
                RecorderAuditEventType.DESTINATION_BLOCKED,
                " alice@example.com ",
                Map.of(
                        "destination\n", "https://proposal.perf.internal/" + "x".repeat(400),
                        "", "ignored"),
                Instant.parse("2026-08-05T10:00:00Z"));

        assertThat(event.actor()).isEqualTo("alice@example.com");
        assertThat(event.detail()).containsKey("destination");
        assertThat(event.detail().get("destination")).endsWith("...");
        assertThat(event.detailJson()).contains("\"destination\":");
        assertThat(event.detailJson()).doesNotContain("\n");
    }
}
