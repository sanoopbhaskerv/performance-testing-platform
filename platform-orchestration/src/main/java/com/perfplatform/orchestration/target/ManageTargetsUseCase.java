package com.perfplatform.orchestration.target;

import com.perfplatform.domain.identity.ApplicationId;
import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.port.TargetRegistry;
import com.perfplatform.domain.port.TeamDirectory;
import com.perfplatform.domain.security.CallerContext;
import com.perfplatform.domain.security.Scope;
import com.perfplatform.domain.spec.TargetClass;
import com.perfplatform.domain.target.RegisteredTarget;
import com.perfplatform.domain.target.TargetAddress;

import java.util.List;
import java.util.Objects;

/** Registers and reads server-side load-test targets. */
public final class ManageTargetsUseCase {

    private final TargetRegistry targets;
    private final TeamDirectory teams;
    private final com.perfplatform.domain.port.TeamProvisioning provisioning;

    public ManageTargetsUseCase(TargetRegistry targets, TeamDirectory teams) {
        this(targets, teams, null);
    }

    public ManageTargetsUseCase(TargetRegistry targets, TeamDirectory teams,
            com.perfplatform.domain.port.TeamProvisioning provisioning) {
        this.provisioning = provisioning;
        this.targets = Objects.requireNonNull(targets, "targets");
        this.teams = Objects.requireNonNull(teams, "teams");
    }

    public record RegisterCommand(String team, String application, String environment, String baseUrl,
            TargetClass targetClass) {
        public RegisterCommand {
            team = require(team, "team");
            application = require(application, "application");
            environment = require(environment, "environment");
            baseUrl = require(baseUrl, "baseUrl");
            targetClass = targetClass == null ? TargetClass.NON_PRODUCTION : targetClass;
        }
    }

    public static class UnknownTeamException extends RuntimeException {
        public UnknownTeamException(String team) {
            super("No team named '" + team + "' is registered.");
        }
    }

    public static class UnknownApplicationException extends RuntimeException {
        public UnknownApplicationException(String application) {
            super("No application named '" + application + "' is registered for this team.");
        }
    }

    public static class ProductionTargetUnsupportedException extends RuntimeException {
        public ProductionTargetUnsupportedException() {
            super("Production target registration requires the approval workflow.");
        }
    }

    public RegisteredTarget register(RegisterCommand command, CallerContext caller) {
        TeamId teamId = teams.findByName(command.team())
                .orElseThrow(() -> new UnknownTeamException(command.team()));
        caller.requireAct(Scope.DEFINITIONS_WRITE, teamId);

        if (command.targetClass() == TargetClass.PRODUCTION) {
            throw new ProductionTargetUnsupportedException();
        }

        TargetAddress address = TargetAddress.parse(command.baseUrl());
        ApplicationId applicationId = teams.findApplication(teamId, command.application())
                .orElseGet(() -> {
                    if (provisioning == null) throw new UnknownApplicationException(command.application());
                    return provisioning.ensureApplication(teamId, command.application(), TargetClass.NON_PRODUCTION);
                });
        return targets.register(teamId, applicationId, command.application(), command.environment(),
                command.targetClass(), address, caller.subject());
    }

    public List<RegisteredTarget> listForCaller(CallerContext caller) {
        return caller.teamIds().stream()
                .filter(teamId -> caller.canAct(Scope.DEFINITIONS_READ, teamId))
                .flatMap(teamId -> targets.listForTeam(teamId).stream())
                .toList();
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
