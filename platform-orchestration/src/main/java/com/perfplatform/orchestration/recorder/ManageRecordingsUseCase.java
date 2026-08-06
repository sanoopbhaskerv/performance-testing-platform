package com.perfplatform.orchestration.recorder;

import com.perfplatform.domain.identity.RecorderSessionId;
import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.importer.SourceImporter;
import com.perfplatform.domain.port.HostAddressResolver;
import com.perfplatform.domain.port.RecorderAuditLog;
import com.perfplatform.domain.port.RecorderBrowser;
import com.perfplatform.domain.port.RecorderBrowser.RecorderBrowserHandle;
import com.perfplatform.domain.port.RecorderBrowser.RecorderBrowserRequest;
import com.perfplatform.domain.port.TargetRegistry;
import com.perfplatform.domain.port.TeamDirectory;
import com.perfplatform.domain.recorder.RecorderAuditEvent;
import com.perfplatform.domain.recorder.RecorderAuditEventType;
import com.perfplatform.domain.recorder.RecorderBlockedDestination;
import com.perfplatform.domain.recorder.RecorderCapture;
import com.perfplatform.domain.recorder.RecorderCaptureSanitizer;
import com.perfplatform.domain.recorder.RecorderDecision;
import com.perfplatform.domain.recorder.RecorderInputEvent;
import com.perfplatform.domain.recorder.RecorderInputKind;
import com.perfplatform.domain.recorder.RecorderRequestKind;
import com.perfplatform.domain.recorder.RecorderRouteGuard;
import com.perfplatform.domain.recorder.RecorderSanitizedCapture;
import com.perfplatform.domain.recorder.RecorderScreencastFrame;
import com.perfplatform.domain.recorder.RecorderSessionPolicy;
import com.perfplatform.domain.recorder.RecorderSessionState;
import com.perfplatform.domain.recorder.RecorderViewport;
import com.perfplatform.domain.security.CallerContext;
import com.perfplatform.domain.validation.ValidationFinding;
import com.perfplatform.domain.validation.ValidationResult;
import com.perfplatform.domain.security.Scope;
import com.perfplatform.domain.target.RegisteredTarget;

import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Starts, drives and audits governed browser-recording authoring sessions. */
public final class ManageRecordingsUseCase {

    /**
     * How much of the audit trail travels with a session response.
     *
     * <p>Read straight from the log rather than through {@link #events}, because a caller holding
     * {@code recordings:cancel} has already been authorized for the session it just stopped and
     * should not need {@code recordings:read} as well to be told the outcome.
     */
    private static final int AUDIT_TAIL = 200;

    private final TeamDirectory teams;
    private final TargetRegistry targets;
    private final RecorderAuditLog audit;
    private final RecorderBrowser browser;
    private final HostAddressResolver hosts;
    private final RecorderSessionRegistry registry;
    private final RecorderBridgeSettings settings;
    private final SourceImporter harImporter;
    private final Clock clock;

    public ManageRecordingsUseCase(TeamDirectory teams, TargetRegistry targets,
            RecorderAuditLog audit, RecorderBrowser browser, HostAddressResolver hosts,
            RecorderSessionRegistry registry, RecorderBridgeSettings settings,
            SourceImporter harImporter, Clock clock) {

        this.teams = Objects.requireNonNull(teams, "teams");
        this.targets = Objects.requireNonNull(targets, "targets");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.browser = Objects.requireNonNull(browser, "browser");
        this.hosts = Objects.requireNonNull(hosts, "hosts");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.harImporter = Objects.requireNonNull(harImporter, "harImporter");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public record StartCommand(String team, String teamId, String application, String environment,
            Integer viewportWidth, Integer viewportHeight) {

        public StartCommand {
            team = team == null ? null : team.trim();
            teamId = teamId == null ? null : teamId.trim();
            if ((team == null || team.isBlank()) && (teamId == null || teamId.isBlank())) {
                throw new IllegalArgumentException("team or teamId must be supplied");
            }
            application = require(application, "application");
            environment = require(environment, "environment");
        }

        public StartCommand(String team, String teamId, String application, String environment) {
            this(team, teamId, application, environment, null, null);
        }

        public StartCommand(String team, String application, String environment) {
            this(team, null, application, environment, null, null);
        }
    }

    /**
     * A recording session as a consumer sees it.
     *
     * @param bridge  what this deployment can actually do, so a pending session explains itself
     */
    public record RecordingSession(
            RecorderSessionId id,
            TeamId teamId,
            String application,
            String environment,
            String target,
            RecorderSessionState status,
            String browserUrl,
            RecorderViewport viewport,
            String bridge,
            String failureCode,
            String failureReason,
            long capturedCount,
            long blockedCount,
            List<RecorderAuditEvent> events) {
    }

    /** Everything a live client needs for one poll. */
    public record LiveUpdate(
            RecorderSessionState status,
            RecorderViewport viewport,
            Optional<RecorderScreencastFrame> frame,
            List<RecorderBlockedDestination> blocked,
            List<LiveRecorderSession.CapturedExchange> captures,
            String failureCode,
            String failureReason) {
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

    /** The target cannot be recorded at all: the address floor refuses it. */
    public static class UnsafeRecorderTargetException extends RuntimeException {
        private final String code;

        public UnsafeRecorderTargetException(String code, String reason) {
            super(reason);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /** The session exists but will not accept this right now. */
    public static class RecorderInputRefusedException extends RuntimeException {
        public RecorderInputRefusedException(String reason) {
            super(reason);
        }
    }

    // ------------------------------------------------------------------- start

    public RecordingSession start(StartCommand command, CallerContext caller) {
        TeamId teamId = resolveTeam(command);
        caller.requireAct(Scope.RECORDINGS_CREATE, teamId);

        RegisteredTarget target = targets.find(teamId, command.application(), command.environment())
                .orElseThrow(() -> new UnknownTargetException(command.application(),
                        command.environment()));

        RecorderSessionId sessionId = RecorderSessionId.generate();
        Instant now = clock.instant();
        RecorderSessionPolicy policy = freezePolicy(target);
        RecorderViewport viewport = viewportFor(command);
        String startUrl = target.address().baseUrl();

        LiveRecorderSession session = new LiveRecorderSession(sessionId, teamId,
                target.applicationName(), target.environment(), startUrl, startUrl,
                policy, viewport, settings, now);
        registry.register(session);

        Map<String, String> requested = new LinkedHashMap<>();
        requested.put("application", target.applicationName());
        requested.put("environment", target.environment());
        requested.put("targetOrigin", startUrl);
        requested.put("bridge", browser.describe());
        requested.put("addressFloor", policy.allowances().isRelaxed()
                ? "relaxed-local-development" : "strict");
        RecorderAuditEvent requestedEvent = audit.append(RecorderAuditEvent.of(sessionId, teamId,
                RecorderAuditEventType.SESSION_REQUESTED, caller.subject(), requested, now));

        if (!browser.isAvailable()) {
            // Honest pending: no browser exists, so the session says so rather than pretending.
            return describe(session, List.of(requestedEvent));
        }

        return attach(session, caller, requestedEvent);
    }

    private RecordingSession attach(LiveRecorderSession session, CallerContext caller,
            RecorderAuditEvent requestedEvent) {

        session.markStarting();
        try {
            RecorderBrowserHandle handle = browser.open(new RecorderBrowserRequest(
                    session.id(), session.policy(), session.viewport(), session.startUrl(),
                    settings.idleTimeout(), settings.deadline(), session));
            Instant startedAt = clock.instant();
            session.markLive(handle, startedAt);

            RecorderAuditEvent started = audit.append(RecorderAuditEvent.of(session.id(),
                    session.teamId(), RecorderAuditEventType.SESSION_STARTED, caller.subject(),
                    Map.of("bridge", browser.describe(),
                            "viewport", session.viewport().width() + "x" + session.viewport().height(),
                            "allowedOrigins",
                            String.valueOf(session.policy().allowedOrigins().size())),
                    startedAt));

            return describe(session, List.of(requestedEvent, started));
        } catch (RuntimeException e) {
            Instant failedAt = clock.instant();
            session.terminate(RecorderSessionState.FAILED, "recorder.bridge.startFailed",
                    e.getMessage(), failedAt);
            RecorderAuditEvent failed = audit.append(RecorderAuditEvent.of(session.id(),
                    session.teamId(), RecorderAuditEventType.SESSION_CANCELLED, caller.subject(),
                    Map.of("reason", "bridge-start-failed",
                            "detail", String.valueOf(e.getMessage())),
                    failedAt));
            return describe(session, List.of(requestedEvent, failed));
        }
    }

    /**
     * Resolves the target's addresses and freezes them for the life of the session.
     *
     * <p>This is the anti-rebinding control (recorder security review §4.2). Resolving once here
     * and pinning the result is what makes the browser's route guard, the generated NetworkPolicy
     * and the browser's own resolver agree; re-resolving later would let a name that passed the
     * floor at start time point somewhere prohibited by the time it is used.
     */
    private RecorderSessionPolicy freezePolicy(RegisteredTarget target) {
        List<InetAddress> addresses = hosts.resolve(target.address().host());
        try {
            return RecorderSessionPolicy.forTarget(target, addresses)
                    .withAllowances(settings.allowances())
                    .build();
        } catch (IllegalArgumentException e) {
            throw new UnsafeRecorderTargetException("recorder.target.refused", e.getMessage());
        }
    }

    private RecorderViewport viewportFor(StartCommand command) {
        if (command.viewportWidth() == null || command.viewportHeight() == null) {
            return settings.defaultViewport();
        }
        return RecorderViewport.clamped(command.viewportWidth(), command.viewportHeight());
    }

    // ------------------------------------------------------------------ driving

    /** One poll, returning immediately with whatever the client has not seen. */
    public LiveUpdate live(RecorderSessionId sessionId, long afterFrame, long afterBlocked,
            long afterCapture, int limit, CallerContext caller) {
        return live(sessionId, afterFrame, afterBlocked, afterCapture, limit, Duration.ZERO, caller);
    }

    /**
     * One poll, optionally waiting for something to happen first.
     *
     * <p>The wait is what makes this affordable. Chrome produces a screencast frame only when the
     * page repaints, so someone reading a static page generates nothing at all — and a client on a
     * fixed interval then spends the whole session asking for it. Waiting server-side turns a
     * recording into roughly one request per event instead of eight per second.
     *
     * @param wait how long to block before answering empty-handed; {@link Duration#ZERO} to return
     *             at once. The API layer bounds it, because a request that never returns is one a
     *             client cannot cancel cleanly.
     */
    public LiveUpdate live(RecorderSessionId sessionId, long afterFrame, long afterBlocked,
            long afterCapture, int limit, Duration wait, CallerContext caller) {

        LiveRecorderSession session = requireLive(sessionId);
        caller.requireAct(Scope.RECORDINGS_READ, session.teamId());

        if (wait != null && !wait.isZero() && !wait.isNegative()) {
            session.awaitUpdate(afterFrame, afterBlocked, afterCapture, wait);
        }

        expireIfStale(session);
        flushBlockedAudit(session);

        return new LiveUpdate(session.state(), session.viewport(),
                session.frameAfter(afterFrame),
                session.blockedAfter(afterBlocked, limit),
                session.capturesAfter(afterCapture, limit),
                session.closeCode().orElse(null),
                session.closeReason().orElse(null));
    }

    /**
     * Applies one brokered interaction.
     *
     * <p>A navigation is route-guarded here as well as in the adapter. The adapter's guard is the
     * one that catches what the <em>page</em> does; this one catches what the <em>client</em> asks
     * for, and refusing it before it reaches the browser is what keeps a blocked navigation out of
     * the browser's history entirely.
     */
    public void input(RecorderSessionId sessionId, RecorderInputEvent event, CallerContext caller) {
        LiveRecorderSession session = requireLive(sessionId);
        caller.requireAct(Scope.RECORDINGS_CREATE, session.teamId());

        Instant now = clock.instant();
        expireIfStale(session);

        if (event.kind() == RecorderInputKind.NAVIGATE) {
            RecorderDecision decision = RecorderRouteGuard.evaluate(session.policy(), event.url(),
                    RecorderRequestKind.TOP_LEVEL_NAVIGATION);
            if (!decision.allowed()) {
                session.onBlocked(event.url(), RecorderRequestKind.TOP_LEVEL_NAVIGATION, decision,
                        now);
                flushBlockedAudit(session);
                throw new RecorderInputRefusedException(decision.reason());
            }
        }

        session.dispatch(event, now).ifPresent(refusal -> {
            throw new RecorderInputRefusedException(refusal);
        });
    }

    public RecordingSession stop(RecorderSessionId sessionId, CallerContext caller) {
        LiveRecorderSession session = requireLive(sessionId);
        caller.requireAct(Scope.RECORDINGS_CANCEL, session.teamId());

        Instant now = clock.instant();
        boolean wasRunning = !session.state().isTerminal();
        session.terminate(RecorderSessionState.CANCELLED, "recorder.session.stopped",
                "Stopped by " + caller.subject() + ".", now);
        flushBlockedAudit(session);

        if (wasRunning) {
            // Cancellation is idempotent: stopping an already-stopped session is not a second
            // security event, so only the first one is audited.
            audit.append(RecorderAuditEvent.of(sessionId, session.teamId(),
                    RecorderAuditEventType.SESSION_CANCELLED, caller.subject(),
                    Map.of("capturedExchanges", String.valueOf(session.capturedCount()),
                            "blockedDestinations", String.valueOf(session.blockedCount())),
                    now));
        }
        return describe(session, audit.events(sessionId, 0, AUDIT_TAIL));
    }

    public RecordingSession describe(RecorderSessionId sessionId, CallerContext caller) {
        LiveRecorderSession session = requireLive(sessionId);
        caller.requireAct(Scope.RECORDINGS_READ, session.teamId());
        expireIfStale(session);
        flushBlockedAudit(session);
        return describe(session, audit.events(sessionId, 0, AUDIT_TAIL));
    }

    /**
     * Ends sessions that went quiet, and audits the fact.
     *
     * <p>Called on a schedule as well as on every read, because a user who closes the browser tab
     * stops polling — and an unattended recording browser sitting inside the runner network is
     * exactly what the idle timeout exists to prevent.
     */
    public int sweep() {
        Instant now = clock.instant();
        List<LiveRecorderSession> expired = registry.expireStale(now);
        for (LiveRecorderSession session : expired) {
            flushBlockedAudit(session);
            audit.append(RecorderAuditEvent.of(session.id(), session.teamId(),
                    RecorderAuditEventType.SESSION_EXPIRED, "system",
                    Map.of("reason", session.closeCode().orElse("recorder.session.idle"),
                            "capturedExchanges", String.valueOf(session.capturedCount())),
                    now));
        }
        registry.evictTerminal(now);
        return expired.size();
    }

    // ------------------------------------------------------------------- draft

    /**
     * Turns what was recorded into draft scenarios.
     *
     * <p>Creates nothing. Like {@code /api/v2/imports/*}, this returns a draft plus findings, so it
     * is safe to call while a recording is still running and pressing it twice costs nothing.
     * Creating the definition stays a separate, deliberate act — which is also what keeps a
     * recording from becoming an executable test without anyone having looked at it
     * (recorder security review §5, CLAUDE.md §23).
     *
     * <p>The recording is rendered as a HAR and handed to the ordinary HAR importer, so it gets the
     * same asset stripping, third-party removal, journey grouping, frequency-derived weights and
     * evidence-backed correlation suggestions as a capture exported from DevTools. See
     * {@link RecorderHarRenderer} for why the intermediate format earns its place.
     */
    public SourceImporter.ImportResult draft(RecorderSessionId sessionId, CallerContext caller) {
        LiveRecorderSession session = requireLive(sessionId);
        caller.requireAct(Scope.RECORDINGS_READ, session.teamId());

        List<LiveRecorderSession.CapturedExchange> captures = session.allCaptures();
        if (captures.isEmpty()) {
            return SourceImporter.ImportResult.nothing(ValidationResult.of(ValidationFinding.warning(
                    "recorder.draft.empty", "/",
                    "This recording captured no requests, so there is nothing to turn into a test.",
                    "Interact with the application in the recorder, then try again. Static assets "
                            + "are deliberately not captured.")));
        }

        SourceImporter.ImportResult imported =
                harImporter.importFrom(RecorderHarRenderer.render(captures));

        ValidationResult findings = imported.findings();
        long dropped = session.droppedCaptures();
        if (dropped > 0) {
            // The buffer drops oldest-first, and the oldest requests in a recording are the sign-in.
            // A draft missing them would look complete and be missing the journey that mattered.
            findings = findings.merge(ValidationResult.of(ValidationFinding.warning(
                    "recorder.draft.truncated", "/",
                    "This recording is longer than the platform retains, so its earliest "
                            + dropped + " request(s) are not in this draft.",
                    "Record shorter journeys, one at a time. The earliest requests are usually "
                            + "the sign-in, so check whether it is missing.")));
        }

        audit.append(RecorderAuditEvent.of(sessionId, session.teamId(),
                RecorderAuditEventType.DRAFT_CREATED, caller.subject(),
                Map.of("scenarios", String.valueOf(imported.scenarios().size()),
                        "capturedExchanges", String.valueOf(captures.size()),
                        "findings", String.valueOf(findings.findings().size())),
                clock.instant()));

        return SourceImporter.ImportResult.of(imported.scenarios(), findings,
                imported.correlationSuggestions());
    }

    // ------------------------------------------------------------------ reading

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

    // ------------------------------------------------------------------ internals

    private RecordingSession describe(LiveRecorderSession session, List<RecorderAuditEvent> events) {
        return new RecordingSession(session.id(), session.teamId(), session.application(),
                session.environment(), session.targetOrigin(), session.state(),
                session.state() == RecorderSessionState.LIVE ? session.startUrl() : null,
                session.viewport(), browser.describe(),
                session.closeCode().orElse(null), session.closeReason().orElse(null),
                session.capturedCount(), session.blockedCount(), events);
    }

    private LiveRecorderSession requireLive(RecorderSessionId sessionId) {
        return registry.find(sessionId)
                .orElseThrow(() -> new UnknownSessionException(sessionId));
    }

    private void expireIfStale(LiveRecorderSession session) {
        Instant now = clock.instant();
        if (session.isPastDeadline(now)) {
            session.terminate(RecorderSessionState.EXPIRED, "recorder.session.deadline",
                    "The recording session reached its maximum length.", now);
        } else if (session.isIdle(now)) {
            session.terminate(RecorderSessionState.EXPIRED, "recorder.session.idle",
                    "The recording session was idle and closed itself.", now);
        }
    }

    /** Writes the audit rows the browser's callback threads deliberately did not write inline. */
    private void flushBlockedAudit(LiveRecorderSession session) {
        for (RecorderBlockedDestination event : session.drainPendingAudit()) {
            audit.append(RecorderAuditEvent.of(session.id(), session.teamId(),
                    RecorderAuditEventType.DESTINATION_BLOCKED, "system",
                    Map.of("code", event.code(),
                            "kind", event.kind().name(),
                            // The origin, never the full URL: a blocked SSO callback carries a
                            // grant code in its query string.
                            "origin", originOf(event.destination())),
                    event.blockedAt()));
        }
    }

    private static String originOf(String destination) {
        try {
            java.net.URI uri = java.net.URI.create(destination);
            if (uri.getHost() == null) {
                return "(unparseable)";
            }
            return uri.getScheme() + "://" + uri.getHost()
                    + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
        } catch (RuntimeException e) {
            return "(unparseable)";
        }
    }

    private TeamId teamFor(RecorderSessionId sessionId) {
        return registry.find(sessionId)
                .map(LiveRecorderSession::teamId)
                .or(() -> audit.events(sessionId, 0, 1).stream()
                        .findFirst()
                        .map(RecorderAuditEvent::teamId))
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
