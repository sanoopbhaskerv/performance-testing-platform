package com.perfplatform.domain.port;

import com.perfplatform.domain.identity.ApplicationId;
import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.spec.TargetClass;
import com.perfplatform.domain.target.RegisteredTarget;
import com.perfplatform.domain.target.TargetAddress;

import java.util.List;
import java.util.Optional;

/** Durable registry of server-approved targets. */
public interface TargetRegistry extends TargetResolver {

    RegisteredTarget register(TeamId teamId, ApplicationId applicationId, String applicationName,
            String environment, TargetClass targetClass, TargetAddress address, String actor);

    Optional<RegisteredTarget> find(TeamId teamId, String applicationName, String environment);

    List<RegisteredTarget> listForTeam(TeamId teamId);

    class UnsafeTargetException extends RuntimeException {
        public UnsafeTargetException(String reason) {
            super(reason);
        }
    }
}
