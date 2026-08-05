package com.perfplatform.orchestration.recorder;

import com.perfplatform.domain.identity.ApplicationId;
import com.perfplatform.domain.identity.RecorderSessionId;
import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.port.RecorderAuditLog;
import com.perfplatform.domain.port.TargetRegistry;
import com.perfplatform.domain.port.TeamDirectory;
import com.perfplatform.domain.recorder.RecorderAuditEvent;
import com.perfplatform.domain.recorder.RecorderAuditEventType;
import com.perfplatform.domain.recorder.RecorderCapture;
import com.perfplatform.domain.security.CallerContext;
import com.perfplatform.domain.security.Scope;
import com.perfplatform.domain.spec.TargetClass;
import com.perfplatform.domain.target.RegisteredTarget;
import com.perfplatform.domain.target.TargetAddress;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManageRecordingsUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-08-05T10:00:00Z");
    private final TeamId team = TeamId.generate();
    private final ApplicationId application = ApplicationId.generate();
    private final InMemoryAudit audit = new InMemoryAudit();
    private ManageRecordingsUseCase useCase;

    @BeforeEach
    void setUp() {
        InMemoryDirectory directory = new InMemoryDirectory(team, application);
        InMemoryTargets targets = new InMemoryTargets(team, application);
        useCase = new ManageRecordingsUseCase(directory, targets, audit,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("starting a session requires recordings:create for the target's team")
    void startRequiresRecorderScope() {
        CallerContext executeOnly = new CallerContext("alice@example.com", Set.of(team),
                Set.of(Scope.RUNS_EXECUTE), false);

        assertThatThrownBy(() -> useCase.start(command(), executeOnly))
                .isInstanceOf(CallerContext.AuthorizationException.class);
    }

    @Test
    @DisplayName("starting a session records an audit event and returns no browser bridge yet")
    void startRecordsAuditEvent() {
        var session = useCase.start(command(), caller(Scope.RECORDINGS_CREATE));

        assertThat(session.status()).isEqualTo("PENDING_BROWSER_BRIDGE");
        assertThat(session.browserUrl()).isNull();
        assertThat(audit.events(session.id(), 0, 10))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.type()).isEqualTo(RecorderAuditEventType.SESSION_REQUESTED);
                    assertThat(event.detail()).containsEntry("targetOrigin",
                            "https://proposal.perf.internal");
                });
    }

    @Test
    @DisplayName("sanitizing a capture records only sanitizer counts in audit detail")
    void sanitizeRecordsCountsOnly() {
        var session = useCase.start(command(), caller(Scope.RECORDINGS_CREATE));

        var sanitized = useCase.sanitize(session.id(), new RecorderCapture(
                "POST",
                "https://proposal.perf.internal/login",
                Map.of("Authorization", "Bearer lower-env-token"),
                "application/json",
                "{\"password\":\"lower-env-password\"}",
                Map.of(),
                null,
                null,
                "{\"cookies\":[]}"),
                caller(Scope.RECORDINGS_CREATE));

        assertThat(sanitized.requestBody()).isNull();
        RecorderAuditEvent event = audit.events(session.id(), 0, 10).get(1);
        assertThat(event.type()).isEqualTo(RecorderAuditEventType.CAPTURE_SANITIZED);
        assertThat(event.detail()).containsEntry("requestBodyKept", "false");
        assertThat(event.detail().toString())
                .doesNotContain("lower-env-token")
                .doesNotContain("lower-env-password");
    }

    private ManageRecordingsUseCase.StartCommand command() {
        return new ManageRecordingsUseCase.StartCommand("advice", null, "proposal", "performance");
    }

    private CallerContext caller(String... scopes) {
        return new CallerContext("alice@example.com", Set.of(team), Set.of(scopes), false);
    }

    private static final class InMemoryDirectory implements TeamDirectory {
        private final TeamId teamId;
        private final ApplicationId applicationId;

        private InMemoryDirectory(TeamId teamId, ApplicationId applicationId) {
            this.teamId = teamId;
            this.applicationId = applicationId;
        }

        @Override
        public Optional<TeamId> findByName(String name) {
            return "advice".equals(name) ? Optional.of(teamId) : Optional.empty();
        }

        @Override
        public Optional<ApplicationId> findApplication(TeamId teamId, String name) {
            return this.teamId.equals(teamId) && "proposal".equals(name)
                    ? Optional.of(applicationId) : Optional.empty();
        }
    }

    private static final class InMemoryTargets implements TargetRegistry {
        private final RegisteredTarget target;

        private InMemoryTargets(TeamId teamId, ApplicationId applicationId) {
            target = new RegisteredTarget(teamId, applicationId, "proposal", "performance",
                    TargetClass.NON_PRODUCTION,
                    TargetAddress.parse("https://proposal.perf.internal"),
                    "system", NOW);
        }

        @Override
        public RegisteredTarget register(TeamId teamId, ApplicationId applicationId,
                String applicationName, String environment, TargetClass targetClass,
                TargetAddress address, String actor) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<RegisteredTarget> find(TeamId teamId, String applicationName,
                String environment) {
            return target.teamId().equals(teamId)
                    && target.applicationName().equals(applicationName)
                    && target.environment().equals(environment)
                    ? Optional.of(target)
                    : Optional.empty();
        }

        @Override
        public Optional<TargetAddress> resolve(TeamId teamId, String applicationName,
                String environment) {
            return find(teamId, applicationName, environment).map(RegisteredTarget::address);
        }

        @Override
        public List<RegisteredTarget> listForTeam(TeamId teamId) {
            return target.teamId().equals(teamId) ? List.of(target) : List.of();
        }
    }

    private static final class InMemoryAudit implements RecorderAuditLog {
        private final List<RecorderAuditEvent> events = new ArrayList<>();
        private long sequence;

        @Override
        public RecorderAuditEvent append(RecorderAuditEvent event) {
            RecorderAuditEvent stored = new RecorderAuditEvent(++sequence, event.sessionId(),
                    event.teamId(), event.type(), event.actor(), event.detail(), event.occurredAt());
            events.add(stored);
            return stored;
        }

        @Override
        public List<RecorderAuditEvent> events(RecorderSessionId sessionId, long afterSequence,
                int limit) {
            return events.stream()
                    .filter(event -> event.sessionId().equals(sessionId))
                    .filter(event -> event.sequence() > afterSequence)
                    .limit(limit)
                    .toList();
        }
    }
}
