package com.perfplatform.orchestration.run;

import com.perfplatform.domain.execution.RuntimeCredential;
import com.perfplatform.domain.identity.TestRunId;
import com.perfplatform.domain.port.SessionPoolProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeCredentialSessionPoolProviderTest {

    private static final Instant NOW = Instant.parse("2026-08-03T10:00:00Z");

    @Test
    @DisplayName("pool sizing has a floor, a per-session concurrency target, and a ceiling")
    void poolSizingIsBounded() {
        assertThat(RuntimeCredentialSessionPoolProvider.poolSizeFor(1)).isEqualTo(5);
        assertThat(RuntimeCredentialSessionPoolProvider.poolSizeFor(200)).isEqualTo(8);
        assertThat(RuntimeCredentialSessionPoolProvider.poolSizeFor(2_000)).isEqualTo(50);
    }

    @Test
    @DisplayName("the foundation provider duplicates a supplied runtime session into the run pool")
    void suppliedSessionBecomesPoolRows() {
        RuntimeCredentialSessionPoolProvider provider =
                new RuntimeCredentialSessionPoolProvider(Duration.ofHours(2));
        TestRunId runId = TestRunId.generate();

        SessionPoolProvider.SessionPool pool = provider.acquire(
                new SessionPoolProvider.SessionPoolRequest(runId, "session",
                        new RuntimeCredential("session", "opaque-token"), 100,
                        Duration.ofMinutes(30), NOW));

        assertThat(pool.leases()).hasSize(5);
        assertThat(pool.leases()).allSatisfy(lease -> {
            assertThat(lease.value()).isEqualTo("opaque-token");
            assertThat(lease.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(2)));
            assertThat(lease.sessionId()).contains(runId.value().toString());
        });
    }
}
