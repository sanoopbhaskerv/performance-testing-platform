package com.perfplatform.orchestration.run;

import com.perfplatform.domain.port.SessionAcquisitionProvider;
import com.perfplatform.domain.port.SessionPoolProvider;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Foundation session provider backed by a trigger-supplied runtime session.
 *
 * <p>It does not log in to an application. It sizes the per-run pool and partitions a supplied
 * session value so the runner contract is exercised now, while a real acquisition provider can
 * replace {@link RuntimeCredentialSessionAcquisitionProvider} later without changing
 * {@link SessionPoolProvider}.
 */
public final class RuntimeCredentialSessionPoolProvider implements SessionPoolProvider {

    static final int USERS_PER_SESSION = 25;
    static final int MIN_POOL_SIZE = 5;
    static final int MAX_POOL_SIZE = 50;

    private static final Duration DEFAULT_SESSION_TTL = Duration.ofHours(12);

    private final SessionAcquisitionProvider acquisition;

    public RuntimeCredentialSessionPoolProvider() {
        this(DEFAULT_SESSION_TTL);
    }

    public RuntimeCredentialSessionPoolProvider(Duration sessionTtl) {
        this(new RuntimeCredentialSessionAcquisitionProvider(sessionTtl));
    }

    public RuntimeCredentialSessionPoolProvider(SessionAcquisitionProvider acquisition) {
        this.acquisition = Objects.requireNonNull(acquisition, "acquisition");
    }

    @Override
    public SessionPool acquire(SessionPoolRequest request) {
        int size = poolSizeFor(request.peakVirtualUsers());
        List<SessionLease> leases = new ArrayList<>(size);
        var minimumValidUntil = request.requestedAt().plus(request.runDeadline());
        for (int index = 0; index < size; index++) {
            leases.add(acquisition.acquire(new SessionAcquisitionProvider.SessionAcquisitionRequest(
                    request.runId(), request.authenticationName(), index, request.seedCredential(),
                    request.requestedAt(), minimumValidUntil)));
        }
        return new SessionPool(request.authenticationName(), leases);
    }

    static int poolSizeFor(int peakVirtualUsers) {
        if (peakVirtualUsers <= 0) {
            return 0;
        }
        int required = (int) Math.ceil(peakVirtualUsers / (double) USERS_PER_SESSION);
        return Math.min(MAX_POOL_SIZE, Math.max(MIN_POOL_SIZE, required));
    }
}
