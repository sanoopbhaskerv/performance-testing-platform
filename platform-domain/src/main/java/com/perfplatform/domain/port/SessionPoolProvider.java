package com.perfplatform.domain.port;

import com.perfplatform.domain.execution.RuntimeCredential;
import com.perfplatform.domain.identity.TestRunId;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Acquires run-scoped application sessions for load generators.
 *
 * <p>This is deliberately a port. The foundation implementation can turn one caller-supplied
 * runtime session into a bounded per-run pool, while a production implementation can log in through
 * an approved identity flow, refresh expiring sessions, or lease already-warmed sessions without
 * changing orchestration, engine rendering or runner manifest construction.
 */
public interface SessionPoolProvider {

    /**
     * Requests sessions for one run.
     *
     * @param runId              run the sessions belong to
     * @param authenticationName authentication reference from the specification
     * @param seedCredential     caller-supplied runtime credential used by the foundation provider
     * @param peakVirtualUsers   peak protocol virtual users requested by the run
     * @param runDeadline        hard wall-clock deadline the sessions must survive
     * @param requestedAt        acquisition time
     */
    record SessionPoolRequest(
            TestRunId runId,
            String authenticationName,
            RuntimeCredential seedCredential,
            int peakVirtualUsers,
            Duration runDeadline,
            Instant requestedAt) {

        public SessionPoolRequest {
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(seedCredential, "seedCredential");
            Objects.requireNonNull(runDeadline, "runDeadline");
            Objects.requireNonNull(requestedAt, "requestedAt");
            if (authenticationName == null || authenticationName.isBlank()) {
                throw new IllegalArgumentException("authenticationName must not be blank");
            }
            authenticationName = authenticationName.trim();
            if (peakVirtualUsers < 0) {
                throw new IllegalArgumentException("peakVirtualUsers must not be negative");
            }
            if (runDeadline.isNegative() || runDeadline.isZero()) {
                throw new IllegalArgumentException("runDeadline must be positive");
            }
        }
    }

    /**
     * One acquired session row.
     *
     * <p>The value is credential material and must not be logged, persisted as a report artifact, or
     * exposed through status APIs.
     */
    record SessionLease(String sessionId, String value, Instant expiresAt) {

        public SessionLease {
            if (sessionId == null || sessionId.isBlank()) {
                throw new IllegalArgumentException("sessionId must not be blank");
            }
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("session value must not be blank");
            }
            Objects.requireNonNull(expiresAt, "expiresAt");
        }

        @Override
        public String toString() {
            return "SessionLease[sessionId=%s, value=<redacted>, expiresAt=%s]"
                    .formatted(sessionId, expiresAt);
        }
    }

    /** Acquired sessions for the run. */
    record SessionPool(String authenticationName, List<SessionLease> leases) {

        public SessionPool {
            if (authenticationName == null || authenticationName.isBlank()) {
                throw new IllegalArgumentException("authenticationName must not be blank");
            }
            authenticationName = authenticationName.trim();
            leases = leases == null ? List.of() : List.copyOf(leases);
        }

        public Instant earliestExpiry() {
            return leases.stream()
                    .map(SessionLease::expiresAt)
                    .min(Instant::compareTo)
                    .orElse(Instant.MAX);
        }
    }

    /**
     * Acquires or refreshes enough sessions for the request.
     *
     * <p>Implementations must return leases whose values are valid until at least the requested run
     * deadline. The orchestration layer verifies that again before provisioning, because a stale
     * session pool would generate a high-volume authentication failure and then report excellent
     * latency for requests that did no useful work.
     */
    SessionPool acquire(SessionPoolRequest request);
}
