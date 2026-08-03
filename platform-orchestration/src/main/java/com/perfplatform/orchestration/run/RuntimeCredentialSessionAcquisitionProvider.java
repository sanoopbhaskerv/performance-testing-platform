package com.perfplatform.orchestration.run;

import com.perfplatform.domain.port.SessionAcquisitionProvider;
import com.perfplatform.domain.port.SessionPoolProvider;

import java.time.Duration;
import java.util.Objects;

/**
 * Foundation session acquisition provider backed by a trigger-supplied runtime credential.
 *
 * <p>It does not log in to an application. It exists so the orchestration path exercises the same
 * provider seam a production implementation will use for real login and refresh acquisition.
 */
public final class RuntimeCredentialSessionAcquisitionProvider
        implements SessionAcquisitionProvider {

    private final Duration sessionTtl;

    public RuntimeCredentialSessionAcquisitionProvider(Duration sessionTtl) {
        this.sessionTtl = Objects.requireNonNull(sessionTtl, "sessionTtl");
        if (sessionTtl.isNegative() || sessionTtl.isZero()) {
            throw new IllegalArgumentException("sessionTtl must be positive");
        }
    }

    @Override
    public SessionPoolProvider.SessionLease acquire(SessionAcquisitionRequest request) {
        if (request.seedCredential() == null) {
            throw new IllegalStateException("The runtime-credential session acquisition provider "
                    + "requires a trigger-supplied runtime credential.");
        }
        return new SessionPoolProvider.SessionLease(
                "%s-%s-%03d".formatted(request.runId().value(), request.authenticationName(),
                        request.sessionIndex()),
                request.seedCredential().value(),
                request.requestedAt().plus(sessionTtl));
    }
}
