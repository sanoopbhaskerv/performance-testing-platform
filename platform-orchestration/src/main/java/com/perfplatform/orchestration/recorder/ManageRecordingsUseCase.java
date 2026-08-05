package com.perfplatform.orchestration.recorder;

import com.perfplatform.domain.identity.RecorderSessionId;
import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.port.RecorderAuditLog;
import com.perfplatform.domain.port.TargetRegistry;
import com.perfplatform.domain.port.TeamDirectory;
import com.perfplatform.domain.recorder.RecorderAuditEvent;
import com.perfplatform.domain.recorder.RecorderAuditEventType;
import com.perfplatform.domain.recorder.RecorderCapture;
import com.perfplatform.domain.recorder.RecorderCaptureSanitizer;
import com.perfplatform.domain.recorder.RecorderSanitizedCapture;
import com.perfplatform.domain.security.CallerContext;
import com.perfplatform.domain.security.Scope;
import com.perfplatform.domain.target.RegisteredTarget;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Starts and audits governed browser-recording authoring sessions. */
public final class ManageRecordingsUseCase {

    private final TeamDirectory teams;
    private final TargetRegistry targets;
    private final RecorderAuditLog audit;
    private final Clock clock;

    public ManageRecordingsUseCase(TeamDirectory teams, TargetRegistry targets,
            RecorderAuditLog audit, Clock clock) {
        this.teams = Objects.requireNonNull(teams, "teams");
        this.targets = Objects.requireNonNull(targets, "targets");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public record StartCommand(String team, String teamId, String application, String environment) {
        public StartCommand {
            team = team == null ? null : team.trim();
            teamId = teamId == null ? null : teamId.trim();
            if ((team == null || team.isBlank()) && (teamId == null || teamId.isBlank())) {
                throw new IllegalArgumentException("team or teamId must be supplied");
            }
            application = require(application, "application");
            environment = require(environment, "environment");
        }

        public StartCommand(String team, String application, String environment) {
            this(team, null, application, environment);
        }
    }

    public record RecordingSession(
            RecorderSessionId id,
            TeamId teamId,
            String application,
            String environment,
            String target,
            String status,
            String browserUrl,
            List<RecorderAuditEvent> events) {
    }

    public static class UnknownTeamException extends RuntimeException {
        public UnknownTeamException(String team) {
            super("No team named '" + team + "' is registered.");
        }
    }

    public static class UnknownTargetException extends RuntimeException {
        public UnknownTargetException(String application, String environment) {
            super("No target is registered for application '" + application
                    + "' in environment '" + environment + "'.");
        }
    }

    public static class UnknownSessionException extends RuntimeException {
        public UnknownSessionException(RecorderSessionId id) {
            super("No recorder session '" + id + "' exists.");
        }
    }

    public RecordingSession start(StartCommand command, CallerContext caller) {
        TeamId teamId = resolveTeam(command);
        caller.requireAct(Scope.RECORDINGS_CREATE, teamId);

        RegisteredTarget target = targets.find(teamId, command.application(), command.environment())
                .orElseThrow(() -> new UnknownTargetException(command.application(), command.environment()));

        RecorderSessionId sessionId = RecorderSessionId.generate();
        Instant now = clock.instant();
        RecorderAuditEvent event = audit.append(RecorderAuditEvent.of(sessionId, teamId,
                RecorderAuditEventType.SESSION_REQUESTED, caller.subject(),
                Map.of(
                        "application", target.applicationName(),
                        "environment", target.environment(),
                        "targetOrigin", target.address().baseUrl(),
                        "status", "PENDING_BROWSER_BRIDGE"),
                now));

        return new RecordingSession(sessionId, teamId, target.applicationName(), target.environment(),
                target.address().baseUrl(), "PENDING_BROWSER_BRIDGE", null, List.of(event));
    }

    public RecorderSanitizedCapture sanitize(RecorderSessionId sessionId, RecorderCapture capture,
            CallerContext caller) {
        TeamId teamId = teamFor(sessionId);
        caller.requireAct(Scope.RECORDINGS_CREATE, teamId);

        RecorderSanitizedCapture sanitized = RecorderCaptureSanitizer.sanitize(capture);
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("findingCount", String.valueOf(sanitized.findings().size()));
        detail.put("requestHeadersKept", String.valueOf(sanitized.requestHeaders().size()));
        detail.put("responseHeadersKept", String.valueOf(sanitized.responseHeaders().size()));
        detail.put("requestBodyKept", Boolean.toString(sanitized.requestBody() != null));
        detail.put("responseBodyKept", Boolean.toString(sanitized.responseBody() != null));
        audit.append(RecorderAuditEvent.of(sessionId, teamId,
                RecorderAuditEventType.CAPTURE_SANITIZED, caller.subject(), detail, clock.instant()));
        return sanitized;
    }

    public List<RecorderAuditEvent> events(RecorderSessionId sessionId, long afterSequence,
            int limit, CallerContext caller) {
        TeamId teamId = teamFor(sessionId);
        caller.requireAct(Scope.RECORDINGS_READ, teamId);
        return audit.events(sessionId, Math.max(0, afterSequence), Math.max(1, Math.min(limit, 500)));
    }

    private TeamId teamFor(RecorderSessionId sessionId) {
        return audit.events(sessionId, 0, 1).stream()
                .findFirst()
                .map(RecorderAuditEvent::teamId)
                .orElseThrow(() -> new UnknownSessionException(sessionId));
    }

    private TeamId resolveTeam(StartCommand command) {
        if (command.teamId() != null && !command.teamId().isBlank()) {
            return TeamId.of(command.teamId());
        }
        return teams.findByName(command.team())
                .orElseThrow(() -> new UnknownTeamException(command.team()));
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
