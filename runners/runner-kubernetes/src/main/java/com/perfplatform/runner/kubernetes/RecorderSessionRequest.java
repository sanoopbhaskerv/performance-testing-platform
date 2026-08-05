package com.perfplatform.runner.kubernetes;

import com.perfplatform.domain.identity.ApplicationId;
import com.perfplatform.domain.identity.RecorderSessionId;
import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.recorder.RecorderSessionPolicy;

import java.time.Duration;
import java.util.Objects;

/**
 * A request to provision one interactive recorder browser.
 *
 * <p>The policy is already resolved and frozen before this reaches Kubernetes. The manifest builder
 * renders only those resolved addresses; it never expands a hostname itself.
 */
public record RecorderSessionRequest(
        RecorderSessionId sessionId,
        TeamId teamId,
        ApplicationId applicationId,
        RecorderSessionPolicy policy,
        Duration deadline) {

    public RecorderSessionRequest {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(applicationId, "applicationId");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(deadline, "deadline");
        if (deadline.isZero() || deadline.isNegative()) {
            throw new IllegalArgumentException("deadline must be positive");
        }
    }
}
