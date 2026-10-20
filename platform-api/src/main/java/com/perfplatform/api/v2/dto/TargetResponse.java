package com.perfplatform.api.v2.dto;

import com.perfplatform.domain.spec.TargetClass;
import com.perfplatform.domain.target.RegisteredTarget;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "Server-approved target address")
public record TargetResponse(String teamId, String applicationId, String application,
        String environment, TargetClass targetClass, String baseUrl, String createdBy,
        Instant createdAt) {

    public static TargetResponse from(RegisteredTarget target) {
        return new TargetResponse(
                target.teamId().toString(),
                target.applicationId().toString(),
                target.applicationName(),
                target.environment(),
                target.targetClass(),
                target.address().baseUrl(),
                target.createdBy(),
                target.createdAt());
    }
}
