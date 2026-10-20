package com.perfplatform.domain.port;

import com.perfplatform.domain.execution.RuntimeCredential;
import com.perfplatform.domain.identity.TestRunId;

import java.time.Instant;
import java.util.Objects;

/**
 * Acquires one application session for a run.
 *
 * <p>This is the provider seam for production login and refresh. The foundation implementation can
 * clone a trigger-supplied session token, while a real implementation can use an approved login
 * flow, refresh token flow, warmed-session broker, or enterprise identity integration without
 * changing pooling, orchestration or runner delivery.
 */
public interface SessionAcquisitionProvider {

    record SessionAcquisitionRequest(
            TestRunId runId,
            String authenticationName,
            int sessionIndex,
            RuntimeCredential seedCredential,
            Instant requestedAt,
            Instant minimumValidUntil) {

        public SessionAcquisitionRequest {
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(requestedAt, "requestedAt");
            Objects.requireNonNull(minimumValidUntil, "minimumValidUntil");
            if (authenticationName == null || authenticationName.isBlank()) {
                throw new IllegalArgumentException("authenticationName must not be blank");
            }
            if (sessionIndex < 0) {
                throw new IllegalArgumentException("sessionIndex must not be negative");
            }
            authenticationName = authenticationName.trim();
        }
    }

    record SessionRefreshRequest(
            TestRunId runId,
            String authenticationName,
            SessionPoolProvider.SessionLease current,
            RuntimeCredential seedCredential,
            Instant requestedAt,
            Instant minimumValidUntil) {

        public SessionRefreshRequest {
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(current, "current");
            Objects.requireNonNull(requestedAt, "requestedAt");
            Objects.requireNonNull(minimumValidUntil, "minimumValidUntil");
            if (authenticationName == null || authenticationName.isBlank()) {
                throw new IllegalArgumentException("authenticationName must not be blank");
            }
            authenticationName = authenticationName.trim();
        }
    }

    SessionPoolProvider.SessionLease acquire(SessionAcquisitionRequest request);

    default SessionPoolProvider.SessionLease refresh(SessionRefreshRequest request) {
        return acquire(new SessionAcquisitionRequest(
                request.runId(),
                request.authenticationName(),
                0,
                request.seedCredential(),
                request.requestedAt(),
                request.minimumValidUntil()));
    }
}
