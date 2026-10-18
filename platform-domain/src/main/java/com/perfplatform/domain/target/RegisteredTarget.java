package com.perfplatform.domain.target;

import com.perfplatform.domain.identity.ApplicationId;
import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.spec.TargetClass;

import java.time.Instant;
import java.util.Objects;

/**
 * A server-side target a team's runs may address.
 *
 * <p>The specification names team, application and environment. It never names the URL; resolving
 * that tuple to this registered target is what keeps target allowlist policy on the server.
 */
public record RegisteredTarget(TeamId teamId, ApplicationId applicationId, String applicationName,
        String environment, TargetClass targetClass, TargetAddress address, String createdBy,
        Instant createdAt) {

    public RegisteredTarget {
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(applicationId, "applicationId");
        applicationName = requireName(applicationName, "applicationName");
        environment = requireName(environment, "environment");
        Objects.requireNonNull(targetClass, "targetClass");
        Objects.requireNonNull(address, "address");
        createdBy = createdBy == null || createdBy.isBlank() ? "system" : createdBy.trim();
        Objects.requireNonNull(createdAt, "createdAt");
    }

    private static String requireName(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
