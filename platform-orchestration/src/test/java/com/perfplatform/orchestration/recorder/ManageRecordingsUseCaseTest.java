package com.perfplatform.orchestration.recorder;

import com.perfplatform.domain.identity.ApplicationId;
import com.perfplatform.domain.identity.RecorderSessionId;
import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.importer.SourceImporter;
import com.perfplatform.domain.port.HostAddressResolver;
import com.perfplatform.domain.port.RecorderAuditLog;
import com.perfplatform.domain.port.TargetRegistry;
import com.perfplatform.domain.port.TeamDirectory;
import com.perfplatform.domain.recorder.RecorderAddressAllowances;
import com.perfplatform.domain.recorder.RecorderAuditEvent;
import com.perfplatform.domain.recorder.RecorderAuditEventType;
import com.perfplatform.domain.recorder.RecorderCapture;
import com.perfplatform.domain.recorder.RecorderDecision;
import com.perfplatform.domain.recorder.RecorderInputEvent;
import com.perfplatform.domain.recorder.RecorderInputKind;
import com.perfplatform.domain.recorder.RecorderMouseButton;
import com.perfplatform.domain.recorder.RecorderRequestKind;
import com.perfplatform.domain.recorder.RecorderSessionState;
import com.perfplatform.domain.recorder.RecorderViewport;
import com.perfplatform.domain.security.CallerContext;
import com.perfplatform.domain.security.Scope;
import com.perfplatform.domain.spec.PerformanceTestSpecification.ScenarioSpec;
import com.perfplatform.domain.spec.SourceType;
import com.perfplatform.domain.spec.TargetClass;
import com.perfplatform.domain.validation.ValidationFinding;
import com.perfplatform.domain.validation.ValidationResult;
import com.perfplatform.domain.target.RegisteredTarget;
import com.perfplatform.domain.target.TargetAddress;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
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
    private final MutableClock clock = new MutableClock(NOW);

    private FakeRecorderBrowser browser;
    private RecorderSessionRegistry registry;
    private RecordingImporter importer;
    private ManageRecordingsUseCase useCase;

    @BeforeEach
    void setUp() {
        browser = new FakeRecorderBrowser();
        useCase = build(browser, RecorderBridgeSettings.defaults(), ip("10.10.20.30"));
    }

    private ManageRecordingsUseCase build(FakeRecorderBrowser bridge,
            RecorderBridgeSettings settings, InetAddress resolved) {
        registry = RecorderSessionRegistry.withDefaults();
        importer = new RecordingImporter();
        return new ManageRecordingsUseCase(new InMemoryDirectory(team, application),
                new InMemoryTargets(team, application), audit, bridge,
                host -> List.of(resolved), registry, settings, importer, clock);
    }

    // ------------------------------------------------------------------ starting

    @Nested
    class Starting {

        @Test
        @DisplayName("starting a session requires recordings:create for the target's team")
        void startRequiresRecorderScope() {
            CallerContext executeOnly = new CallerContext("alice@example.com", Set.of(team),
                    Set.of(Scope.RUNS_EXECUTE), false);

            assertThatThrownBy(() -> useCase.start(command(), executeOnly))
                    .isInstanceOf(CallerContext.AuthorizationException.class);
        }

        @Test
        @DisplayName("a session with a bridge goes live and audits both request and start")
        void startGoesLive() {
            var session = useCase.start(command(), caller(Scope.RECORDINGS_CREATE));

            assertThat(session.status()).isEqualTo(RecorderSessionState.LIVE);
            assertThat(session.browserUrl()).isEqualTo("https://proposal.perf.internal");
            assertThat(audit.events(session.id(), 0, 10))
                    .extracting(RecorderAuditEvent::type)
                    .containsExactly(RecorderAuditEventType.SESSION_REQUESTED,
                            RecorderAuditEventType.SESSION_STARTED);
        }

        @Test
        @DisplayName("without a bridge the session stays pending and says which bridge is missing")
        void pendingWhenNoBridge() {
            useCase = build(FakeRecorderBrowser.unavailable(), RecorderBridgeSettings.defaults(),
                    ip("10.10.20.30"));

            var session = useCase.start(command(), caller(Scope.RECORDINGS_CREATE));

            assertThat(session.status()).isEqualTo(RecorderSessionState.PENDING_BROWSER_BRIDGE);
            assertThat(session.browserUrl()).isNull();
            assertThat(session.bridge()).isEqualTo("fake");
        }

        @Test
        @DisplayName("a bridge that fails to start fails the session rather than leaving it pending")
        void failedBridgeFailsSession() {
            useCase = build(FakeRecorderBrowser.failingToStart("no chrome on PATH"),
                    RecorderBridgeSettings.defaults(), ip("10.10.20.30"));

            var session = useCase.start(command(), caller(Scope.RECORDINGS_CREATE));

            assertThat(session.status()).isEqualTo(RecorderSessionState.FAILED);
            assertThat(session.failureReason()).contains("no chrome");
        }

        @Test
        @DisplayName("the session's destination addresses are frozen before the browser opens")
        void addressesAreFrozen() {
            useCase.start(command(), caller(Scope.RECORDINGS_CREATE));

            assertThat(browser.lastRequest.policy().allowedOrigins().values())
                    .singleElement()
                    .satisfies(allowed -> assertThat(allowed.resolvedAddresses())
                            .containsExactly(ip("10.10.20.30")));
        }

        @Test
        @DisplayName("a target that resolves into the metadata range cannot be recorded")
        void metadataTargetIsRefused() {
            useCase = build(new FakeRecorderBrowser(), RecorderBridgeSettings.defaults(),
                    ip("169.254.169.254"));

            assertThatThrownBy(() -> useCase.start(command(), caller(Scope.RECORDINGS_CREATE)))
                    .isInstanceOf(ManageRecordingsUseCase.UnsafeRecorderTargetException.class);
        }

        @Test
        @DisplayName("a loopback target is refused under the strict floor and allowed under the relaxed one")
        void loopbackNeedsTheExplicitAllowance() {
            useCase = build(new FakeRecorderBrowser(), RecorderBridgeSettings.defaults(),
                    ip("127.0.0.1"));
            assertThatThrownBy(() -> useCase.start(command(), caller(Scope.RECORDINGS_CREATE)))
                    .isInstanceOf(ManageRecordingsUseCase.UnsafeRecorderTargetException.class);

            useCase = build(new FakeRecorderBrowser(), relaxed(), ip("127.0.0.1"));
            var session = useCase.start(command(), caller(Scope.RECORDINGS_CREATE));

            assertThat(session.status()).isEqualTo(RecorderSessionState.LIVE);
            assertThat(audit.events(session.id(), 0, 10).get(0).detail())
                    .containsEntry("addressFloor", "relaxed-local-development");
        }

        @Test
        @DisplayName("a requested viewport is clamped, not trusted")
        void viewportIsClamped() {
            var session = useCase.start(new ManageRecordingsUseCase.StartCommand("advice", null,
                    "proposal", "performance", 99_999, 10), caller(Scope.RECORDINGS_CREATE));

            assertThat(session.viewport()).isEqualTo(new RecorderViewport(
                    RecorderViewport.MAX_DIMENSION, RecorderViewport.MIN_DIMENSION));
        }
    }

    // -------------------------------------------------------------------- input

    @Nested
    class Input {

        @Test
        @DisplayName("brokered input reaches the browser")
        void inputIsDispatched() {
            var session = useCase.start(command(), caller(Scope.RECORDINGS_CREATE));

            useCase.input(session.id(), RecorderInputEvent.mouse(RecorderInputKind.MOUSE_PRESSED,
                    10, 20, RecorderMouseButton.LEFT, 1, 0), caller(Scope.RECORDINGS_CREATE));

            assertThat(browser.dispatched).singleElement()
                    .satisfies(event -> assertThat(event.kind())
                            .isEqualTo(RecorderInputKind.MOUSE_PRESSED));
        }

        @Test
        @DisplayName("a navigation outside the allowlist never reaches the browser")
        void navigationIsGuardedBeforeDispatch() {
            var session = useCase.start(command(), caller(Scope.RECORDINGS_CREATE));

            assertThatThrownBy(() -> useCase.input(session.id(),
                    RecorderInputEvent.navigate("https://evil.example/steal"),
                    caller(Scope.RECORDINGS_CREATE)))
                    .isInstanceOf(ManageRecordingsUseCase.RecorderInputRefusedException.class)
                    .hasMessageContaining("allowlist");

            assertThat(browser.dispatched).isEmpty();
        }

        @Test
        @DisplayName("a blocked navigation is audited by origin, without its query string")
        void blockedNavigationIsAuditedWithoutSecrets() {
            var session = useCase.start(command(), caller(Scope.RECORDINGS_CREATE));

            assertThatThrownBy(() -> useCase.input(session.id(),
                    RecorderInputEvent.navigate("https://sso.example/callback?code=secret-grant"),
                    caller(Scope.RECORDINGS_CREATE)))
                    .isInstanceOf(ManageRecordingsUseCase.RecorderInputRefusedException.class);

            RecorderAuditEvent blocked = audit.events(session.id(), 0, 10).stream()
                    .filter(event -> event.type() == RecorderAuditEventType.DESTINATION_BLOCKED)
                    .findFirst()
                    .orElseThrow();
            assertThat(blocked.detail()).containsEntry("origin", "https://sso.example");
            assertThat(blocked.detail().toString()).doesNotContain("secret-grant");
        }

        @Test
        @DisplayName("input beyond the rate limit is refused")
        void inputIsRateLimited() {
            var session = useCase.start(command(), caller(Scope.RECORDINGS_CREATE));
            CallerContext caller = caller(Scope.RECORDINGS_CREATE);
            RecorderInputEvent move = RecorderInputEvent.mouse(RecorderInputKind.MOUSE_MOVED,
                    1, 1, RecorderMouseButton.NONE, 0, 0);

            for (int i = 0; i < 60; i++) {
                useCase.input(session.id(), move, caller);
            }

            assertThatThrownBy(() -> useCase.input(session.id(), move, caller))
                    .isInstanceOf(ManageRecordingsUseCase.RecorderInputRefusedException.class)
                    .hasMessageContaining("slow down");
        }

        @Test
        @DisplayName("a pointer outside the recorded viewport is refused")
        void pointerMustBeInsideViewport() {
            var session = useCase.start(command(), caller(Scope.RECORDINGS_CREATE));

            assertThatThrownBy(() -> useCase.input(session.id(),
                    RecorderInputEvent.mouse(RecorderInputKind.MOUSE_PRESSED, 5_000, 10,
                            RecorderMouseButton.LEFT, 1, 0), caller(Scope.RECORDINGS_CREATE)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("input needs recordings:create, not merely read")
        void inputRequiresWriteScope() {
            var session = useCase.start(command(), caller(Scope.RECORDINGS_CREATE));

            assertThatThrownBy(() -> useCase.input(session.id(),
                    RecorderInputEvent.of(RecorderInputKind.RELOAD), caller(Scope.RECORDINGS_READ)))
                    .isInstanceOf(CallerContext.AuthorizationException.class);
        }
    }

    // ------------------------------------------------------------------ streaming

    @Nested
    class Streaming {

        @Test
        @DisplayName("a poll returns the newest frame after the client's sequence")
        void newestFrameWins() {
            var session = useCase.start(command(), caller(Scope.RECORDINGS_CREATE));
            browser.lastHandle.pushFrame("first", NOW);
            browser.lastHandle.pushFrame("second", NOW);

            var update = useCase.live(session.id(), 0, 0, 0, 50, caller(Scope.RECORDINGS_READ));

            assertThat(update.frame()).isPresent();
            assertThat(update.frame().orElseThrow().imageBase64()).isEqualTo("second");
            assertThat(update.frame().orElseThrow().sequence()).isEqualTo(2);
        }

        @Test
        @DisplayName("a client that is already current gets no frame rather than a repeat")
        void noFrameWhenCurrent() {
            var session = useCase.start(command(), caller(Scope.RECORDINGS_CREATE));
            browser.lastHandle.pushFrame("only", NOW);

            var update = useCase.live(session.id(), 1, 0, 0, 50, caller(Scope.RECORDINGS_READ));

            assertThat(update.frame()).isEmpty();
        }

        @Test
        @DisplayName("frames older than the buffer are dropped rather than growing the heap")
        void frameBufferIsBounded() {
            var session = useCase.start(command(), caller(Scope.RECORDINGS_CREATE));
            for (int i = 0; i < 30; i++) {
                browser.lastHandle.pushFrame("frame-" + i, NOW);
            }

            var update = useCase.live(session.id(), 0, 0, 0, 50, caller(Scope.RECORDINGS_READ));

            assertThat(update.frame().orElseThrow().imageBase64()).isEqualTo("frame-29");
            assertThat(registry.find(session.id()).orElseThrow().droppedFrames()).isEqualTo(22);
        }

        @Test
        @DisplayName("captures are sanitized before a client can read them")
        void capturesAreSanitized() {
            var session = useCase.start(command(), caller(Scope.RECORDINGS_CREATE));
            browser.lastHandle.listener().onCapture(new RecorderCapture("POST",
                    "https://proposal.perf.internal/login",
                    Map.of("Authorization", "Bearer lower-env-token"),
                    "application/json", "{\"password\":\"lower-env-password\"}",
                    Map.of(), null, null, "{\"cookies\":[]}"), NOW);

            var update = useCase.live(session.id(), 0, 0, 0, 50, caller(Scope.RECORDINGS_READ));

            assertThat(update.captures()).singleElement().satisfies(exchange -> {
                assertThat(exchange.capture().requestBody()).isNull();
                assertThat(exchange.capture().requestHeaders().toString())
                        .doesNotContain("lower-env-token");
            });
        }

        @Test
        @DisplayName("a repeatedly blocked origin is audited once, not once per attempt")
        void blockedAuditIsDeduplicated() {
            var session = useCase.start(command(), caller(Scope.RECORDINGS_CREATE));
            RecorderDecision decision = RecorderDecision.block(
                    "recorder.destination.outsideAllowlist", "nope");
            for (int i = 0; i < 20; i++) {
                browser.lastHandle.listener().onBlocked("https://ads.example/pixel?n=" + i,
                        RecorderRequestKind.SUBRESOURCE, decision, NOW);
            }

            var update = useCase.live(session.id(), 0, 0, 0, 50, caller(Scope.RECORDINGS_READ));

            assertThat(update.blocked()).hasSize(20);
            assertThat(audit.events(session.id(), 0, 100))
                    .filteredOn(event -> event.type() == RecorderAuditEventType.DESTINATION_BLOCKED)
                    .hasSize(1);
        }

        /**
         * Chrome emits a frame only on repaint, so a static page produces none. Without a wait the
         * client asked eight times a second for nothing: a five-minute recording was two thousand
         * empty requests.
         */
        @Test
        @DisplayName("a poll waits rather than returning empty straight away")
        void pollWaitsForSomethingToHappen() throws Exception {
            var session = useCase.start(command(), caller(Scope.RECORDINGS_CREATE));
            var handle = browser.lastHandle;

            java.util.concurrent.CompletableFuture<ManageRecordingsUseCase.LiveUpdate> polling =
                    java.util.concurrent.CompletableFuture.supplyAsync(() ->
                            useCase.live(session.id(), 0, 0, 0, 50, Duration.ofSeconds(5),
                                    caller(Scope.RECORDINGS_READ)));

            // Still waiting: nothing has happened yet.
            Thread.sleep(150);
            assertThat(polling).isNotDone();

            handle.pushFrame("first", NOW);

            assertThat(polling.get(5, java.util.concurrent.TimeUnit.SECONDS).frame())
                    .isPresent();
        }

        @Test
        @DisplayName("a poll that waits and sees nothing still answers, rather than hanging")
        void pollGivesUpOnTime() {
            var session = useCase.start(command(), caller(Scope.RECORDINGS_CREATE));

            long startedAt = System.nanoTime();
            var update = useCase.live(session.id(), 0, 0, 0, 50, Duration.ofMillis(200),
                    caller(Scope.RECORDINGS_READ));
            long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

            assertThat(update.frame()).isEmpty();
            assertThat(elapsedMillis).isGreaterThanOrEqualTo(150).isLessThan(5_000);
        }

        @Test
        @DisplayName("a session that ends releases a waiting poll immediately")
        void endingWakesAWaitingPoll() throws Exception {
            var session = useCase.start(command(), caller(Scope.RECORDINGS_CREATE));

            java.util.concurrent.CompletableFuture<ManageRecordingsUseCase.LiveUpdate> polling =
                    java.util.concurrent.CompletableFuture.supplyAsync(() ->
                            useCase.live(session.id(), 0, 0, 0, 50, Duration.ofSeconds(10),
                                    caller(Scope.RECORDINGS_READ)));

            Thread.sleep(150);
            assertThat(polling).isNotDone();

            useCase.stop(session.id(), caller(Scope.RECORDINGS_CANCEL));

            assertThat(polling.get(5, java.util.concurrent.TimeUnit.SECONDS).status())
                    .isEqualTo(RecorderSessionState.CANCELLED);
        }

        @Test
        @DisplayName("a client that is behind is answered at once, without waiting")
        void behindClientIsNotMadeToWait() {
            var session = useCase.start(command(), caller(Scope.RECORDINGS_CREATE));
            browser.lastHandle.pushFrame("already-here", NOW);

            long startedAt = System.nanoTime();
            var update = useCase.live(session.id(), 0, 0, 0, 50, Duration.ofSeconds(10),
                    caller(Scope.RECORDINGS_READ));

            assertThat(update.frame()).isPresent();
            assertThat((System.nanoTime() - startedAt) / 1_000_000).isLessThan(1_000);
        }

        @Test
        @DisplayName("reading a session requires recordings:read")
        void readRequiresScope() {
            var session = useCase.start(command(), caller(Scope.RECORDINGS_CREATE));

            assertThatThrownBy(() -> useCase.live(session.id(), 0, 0, 0, 50,
                    new CallerContext("bob@example.com", Set.of(team),
                            Set.of(Scope.RUNS_READ), false)))
                    .isInstanceOf(CallerContext.AuthorizationException.class);
        }

        @Test
        @DisplayName("a caller from another team cannot read the session")
        void crossTeamReadIsRefused() {
            var session = useCase.start(command(), caller(Scope.RECORDINGS_CREATE));

            assertThatThrownBy(() -> useCase.live(session.id(), 0, 0, 0, 50,
                    new CallerContext("mallory@example.com", Set.of(TeamId.generate()),
                            Set.of(Scope.RECORDINGS_READ), false)))
                    .isInstanceOf(CallerContext.AuthorizationException.class);
        }
    }

    // ------------------------------------------------------------------- ending

    @Nested
    class Ending {

        @Test
        @DisplayName("stopping closes the browser and audits once")
        void stopClosesBrowser() {
            var session = useCase.start(command(), caller(Scope.RECORDINGS_CREATE));

            var stopped = useCase.stop(session.id(), caller(Scope.RECORDINGS_CANCEL));

            assertThat(stopped.status()).isEqualTo(RecorderSessionState.CANCELLED);
            assertThat(browser.lastHandle.closeCount).isEqualTo(1);
            assertThat(audit.events(session.id(), 0, 100))
                    .filteredOn(event -> event.type() == RecorderAuditEventType.SESSION_CANCELLED)
                    .hasSize(1);
        }

        @Test
        @DisplayName("stopping twice is idempotent and audits only the first")
        void stopIsIdempotent() {
            var session = useCase.start(command(), caller(Scope.RECORDINGS_CREATE));

            useCase.stop(session.id(), caller(Scope.RECORDINGS_CANCEL));
            var second = useCase.stop(session.id(), caller(Scope.RECORDINGS_CANCEL));

            assertThat(second.status()).isEqualTo(RecorderSessionState.CANCELLED);
            assertThat(browser.lastHandle.closeCount).isEqualTo(1);
            assertThat(audit.events(session.id(), 0, 100))
                    .filteredOn(event -> event.type() == RecorderAuditEventType.SESSION_CANCELLED)
                    .hasSize(1);
        }

        @Test
        @DisplayName("stopping requires recordings:cancel")
        void stopRequiresCancelScope() {
            var session = useCase.start(command(), caller(Scope.RECORDINGS_CREATE));

            assertThatThrownBy(() -> useCase.stop(session.id(), caller(Scope.RECORDINGS_READ)))
                    .isInstanceOf(CallerContext.AuthorizationException.class);
        }

        @Test
        @DisplayName("an idle session expires, closes its browser and is audited")
        void idleSessionExpires() {
            var session = useCase.start(command(), caller(Scope.RECORDINGS_CREATE));
            clock.advance(Duration.ofMinutes(6));

            assertThat(useCase.sweep()).isEqualTo(1);
            assertThat(browser.lastHandle.closeCount).isEqualTo(1);
            assertThat(audit.events(session.id(), 0, 100))
                    .extracting(RecorderAuditEvent::type)
                    .contains(RecorderAuditEventType.SESSION_EXPIRED);
        }

        @Test
        @DisplayName("a session past its deadline expires even while it is being used")
        void deadlineBeatsActivity() {
            var session = useCase.start(command(), caller(Scope.RECORDINGS_CREATE));
            CallerContext caller = caller(Scope.RECORDINGS_CREATE);

            // Keep the session busy so the idle timeout never fires: only the deadline can end it.
            for (int i = 0; i < 14; i++) {
                clock.advance(Duration.ofMinutes(2));
                useCase.input(session.id(), RecorderInputEvent.of(RecorderInputKind.RELOAD), caller);
            }
            assertThat(registry.find(session.id()).orElseThrow().state())
                    .isEqualTo(RecorderSessionState.LIVE);

            clock.advance(Duration.ofMinutes(4));
            assertThatThrownBy(() -> useCase.input(session.id(),
                    RecorderInputEvent.of(RecorderInputKind.RELOAD), caller))
                    .isInstanceOf(ManageRecordingsUseCase.RecorderInputRefusedException.class)
                    .hasMessageContaining("EXPIRED");
            assertThat(registry.find(session.id()).orElseThrow().closeCode())
                    .contains("recorder.session.deadline");
        }

        @Test
        @DisplayName("a terminal session accepts no further input")
        void terminalSessionRefusesInput() {
            var session = useCase.start(command(), caller(Scope.RECORDINGS_CREATE));
            useCase.stop(session.id(), caller(Scope.RECORDINGS_CANCEL));

            assertThatThrownBy(() -> useCase.input(session.id(),
                    RecorderInputEvent.of(RecorderInputKind.RELOAD), caller(Scope.RECORDINGS_CREATE)))
                    .isInstanceOf(ManageRecordingsUseCase.RecorderInputRefusedException.class)
                    .hasMessageContaining("CANCELLED");
        }

        /**
         * The console polls every 120ms. Forgetting a session the instant it expires turned the
         * next poll into a 404 — which the client could only read as "no such session", and which
         * it then retried eight times a second indefinitely.
         */
        @Test
        @DisplayName("an expired session stays readable, so the last poll says why it ended")
        void expiredSessionRemainsReadable() {
            var session = useCase.start(command(), caller(Scope.RECORDINGS_CREATE));
            clock.advance(Duration.ofMinutes(6));
            useCase.sweep();

            var update = useCase.live(session.id(), 0, 0, 0, 50, caller(Scope.RECORDINGS_READ));

            assertThat(update.status()).isEqualTo(RecorderSessionState.EXPIRED);
            assertThat(update.failureCode()).isEqualTo("recorder.session.idle");
        }

        @Test
        @DisplayName("an ended session is forgotten once nobody could still be reading it")
        void endedSessionIsEventuallyForgotten() {
            var session = useCase.start(command(), caller(Scope.RECORDINGS_CREATE));
            useCase.stop(session.id(), caller(Scope.RECORDINGS_CANCEL));

            clock.advance(Duration.ofMinutes(11));
            useCase.sweep();

            assertThatThrownBy(() -> useCase.live(session.id(), 0, 0, 0, 50,
                    caller(Scope.RECORDINGS_READ)))
                    .isInstanceOf(ManageRecordingsUseCase.UnknownSessionException.class);
        }

        @Test
        @DisplayName("a browser that dies on its own fails the session")
        void browserCrashFailsSession() {
            var session = useCase.start(command(), caller(Scope.RECORDINGS_CREATE));

            browser.lastHandle.listener().onClosed("recorder.browser.failed.exit",
                    "Chrome exited with 133", NOW);

            assertThat(registry.find(session.id()).orElseThrow().state())
                    .isEqualTo(RecorderSessionState.FAILED);
        }
    }

    // ------------------------------------------------------------------ capture

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
        RecorderAuditEvent event = audit.events(session.id(), 0, 10).stream()
                .filter(candidate -> candidate.type() == RecorderAuditEventType.CAPTURE_SANITIZED)
                .findFirst()
                .orElseThrow();
        assertThat(event.detail()).containsEntry("requestBodyKept", "false");
        assertThat(event.detail().toString())
                .doesNotContain("lower-env-token")
                .doesNotContain("lower-env-password");
    }

    // -------------------------------------------------------------------- draft

    @Nested
    class Draft {

        @Test
        @DisplayName("a recording is handed to the HAR importer, not re-parsed by a second one")
        void recordingBecomesHar() {
            var session = useCase.start(command(), caller(Scope.RECORDINGS_CREATE));
            capture(session.id(), "GET", "http://proposal.perf.internal/orders", 200, "page-1");

            var result = useCase.draft(session.id(), caller(Scope.RECORDINGS_READ));

            assertThat(importer.lastDocument).contains("\"pageref\":\"page-1\"");
            assertThat(importer.lastDocument).contains("http://proposal.perf.internal/orders");
            assertThat(result.scenarios()).hasSize(1);
        }

        @Test
        @DisplayName("drafting audits that a draft was created, with counts and no URLs")
        void draftIsAudited() {
            var session = useCase.start(command(), caller(Scope.RECORDINGS_CREATE));
            capture(session.id(), "POST", "http://proposal.perf.internal/login?token=secret", 200,
                    "page-1");

            useCase.draft(session.id(), caller(Scope.RECORDINGS_READ));

            RecorderAuditEvent created = audit.events(session.id(), 0, 100).stream()
                    .filter(event -> event.type() == RecorderAuditEventType.DRAFT_CREATED)
                    .findFirst()
                    .orElseThrow();
            assertThat(created.detail()).containsEntry("capturedExchanges", "1");
            assertThat(created.detail().toString()).doesNotContain("secret");
        }

        @Test
        @DisplayName("a recording that captured nothing says so rather than producing an empty test")
        void emptyRecordingExplainsItself() {
            var session = useCase.start(command(), caller(Scope.RECORDINGS_CREATE));

            var result = useCase.draft(session.id(), caller(Scope.RECORDINGS_READ));

            assertThat(result.isEmpty()).isTrue();
            assertThat(result.findings().findings())
                    .extracting(ValidationFinding::code)
                    .containsExactly("recorder.draft.empty");
        }

        /**
         * The buffer drops oldest-first, and a recording's oldest requests are its sign-in. A draft
         * silently missing them would look complete.
         */
        @Test
        @DisplayName("a truncated recording warns that its earliest requests are missing")
        void truncationIsReported() {
            useCase = build(new FakeRecorderBrowser(), smallBuffer(), ip("10.10.20.30"));
            var session = useCase.start(command(), caller(Scope.RECORDINGS_CREATE));
            for (int i = 0; i < 5; i++) {
                capture(session.id(), "GET", "http://proposal.perf.internal/" + i, 200, "page-1");
            }

            var result = useCase.draft(session.id(), caller(Scope.RECORDINGS_READ));

            assertThat(result.findings().findings())
                    .extracting(ValidationFinding::code)
                    .contains("recorder.draft.truncated");
        }

        @Test
        @DisplayName("drafting requires recordings:read for the session's team")
        void draftRequiresScope() {
            var session = useCase.start(command(), caller(Scope.RECORDINGS_CREATE));

            assertThatThrownBy(() -> useCase.draft(session.id(),
                    new CallerContext("mallory@example.com", Set.of(TeamId.generate()),
                            Set.of(Scope.RECORDINGS_READ), false)))
                    .isInstanceOf(CallerContext.AuthorizationException.class);
        }

        @Test
        @DisplayName("drafting creates no definition — it is safe to call while still recording")
        void draftCreatesNothing() {
            var session = useCase.start(command(), caller(Scope.RECORDINGS_CREATE));
            capture(session.id(), "GET", "http://proposal.perf.internal/a", 200, "page-1");

            useCase.draft(session.id(), caller(Scope.RECORDINGS_READ));
            useCase.draft(session.id(), caller(Scope.RECORDINGS_READ));

            assertThat(registry.find(session.id()).orElseThrow().state())
                    .isEqualTo(RecorderSessionState.LIVE);
        }
    }

    private void capture(RecorderSessionId sessionId, String method, String url, int status,
            String journeyRef) {
        registry.find(sessionId).orElseThrow().onCapture(new RecorderCapture(method, url,
                Map.of("accept", "application/json"), null, null, Map.of(), "application/json",
                "{}", null, status, journeyRef), NOW);
    }

    private static RecorderBridgeSettings smallBuffer() {
        RecorderBridgeSettings defaults = RecorderBridgeSettings.defaults();
        return new RecorderBridgeSettings(defaults.defaultViewport(), defaults.idleTimeout(),
                defaults.deadline(), defaults.frameBufferSize(), 2, defaults.allowances());
    }

    /** Records the document it was handed and answers with one scenario per entry. */
    private static final class RecordingImporter implements SourceImporter {
        private String lastDocument;

        @Override
        public SourceType sourceType() {
            return SourceType.HAR;
        }

        @Override
        public ImportResult importFrom(String document) {
            lastDocument = document;
            int entries = document.split("\"pageref\"", -1).length - 1;
            return ImportResult.of(
                    java.util.stream.IntStream.range(0, Math.min(1, entries))
                            .mapToObj(index -> new ScenarioSpec("recorded-" + index, 100, null,
                                    "GET", "/", Map.of(), Map.of(), null, List.of()))
                            .toList(),
                    ValidationResult.valid());
        }
    }

    // ------------------------------------------------------------------ fixtures

    private static RecorderBridgeSettings relaxed() {
        RecorderBridgeSettings defaults = RecorderBridgeSettings.defaults();
        return new RecorderBridgeSettings(defaults.defaultViewport(), defaults.idleTimeout(),
                defaults.deadline(), defaults.frameBufferSize(), defaults.eventBufferSize(),
                RecorderAddressAllowances.permittingLoopback());
    }

    private ManageRecordingsUseCase.StartCommand command() {
        return new ManageRecordingsUseCase.StartCommand("advice", null, "proposal", "performance");
    }

    private CallerContext caller(String... scopes) {
        return new CallerContext("alice@example.com", Set.of(team), Set.of(scopes), false);
    }

    private static InetAddress ip(String literal) {
        try {
            return InetAddress.getByName(literal);
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration amount) {
            now = now.plus(amount);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
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
        public synchronized RecorderAuditEvent append(RecorderAuditEvent event) {
            RecorderAuditEvent stored = new RecorderAuditEvent(++sequence, event.sessionId(),
                    event.teamId(), event.type(), event.actor(), event.detail(), event.occurredAt());
            events.add(stored);
            return stored;
        }

        @Override
        public synchronized List<RecorderAuditEvent> events(RecorderSessionId sessionId,
                long afterSequence, int limit) {
            return events.stream()
                    .filter(event -> event.sessionId().equals(sessionId))
                    .filter(event -> event.sequence() > afterSequence)
                    .limit(limit)
                    .toList();
        }
    }
}
