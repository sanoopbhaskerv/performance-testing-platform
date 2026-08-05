package com.perfplatform.runner.chrome;

import com.fasterxml.jackson.databind.JsonNode;
import com.perfplatform.domain.identity.RecorderSessionId;
import com.perfplatform.domain.port.RecorderBrowser;
import com.perfplatform.domain.port.RecorderBrowserListener;
import com.perfplatform.domain.recorder.RecorderCapture;
import com.perfplatform.domain.recorder.RecorderDecision;
import com.perfplatform.domain.recorder.RecorderInputEvent;
import com.perfplatform.domain.recorder.RecorderInputKind;
import com.perfplatform.domain.recorder.RecorderRequestKind;
import com.perfplatform.domain.recorder.RecorderRouteGuard;
import com.perfplatform.domain.recorder.RecorderSessionPolicy;
import com.perfplatform.domain.recorder.RecorderViewport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Drives a local Chrome over the DevTools Protocol as one recording session.
 *
 * <p>Local and development profiles only, for the reason CLAUDE.md §15 gives about JMeter: this
 * starts a child process inside the control-plane container, and a control plane must not be where
 * user-driven browsers run. The shared-runner path puts the same browser in a recorder Pod behind
 * the same {@link RecorderBrowser} port, which is why the orchestration layer above needs no
 * knowledge of which one it has.
 *
 * <h2>What the browser is allowed to reach</h2>
 *
 * <p>Three independent controls, deliberately not one:
 *
 * <ol>
 *   <li>Chrome's resolver is pinned to the session's frozen addresses and refuses every other
 *       name ({@link ChromeCommandLine}).</li>
 *   <li>Every request is paused and put through {@link RecorderRouteGuard} before it leaves.</li>
 *   <li>In a cell, a NetworkPolicy built from the same frozen addresses enforces it below the
 *       browser entirely.</li>
 * </ol>
 *
 * <p>The third is the one that holds if this class has a bug, which is why the recorder security
 * review (§4.2) treats browser-layer routing as feedback rather than as the control.
 *
 * <h2>Known gap</h2>
 *
 * <p>The {@code Fetch} domain does not pause WebSocket handshakes, so a WebSocket to an
 * unlisted origin is caught by the pinned resolver and by network policy rather than by the route
 * guard, and does not produce a blocked-destination event the user can see.
 */
public final class LocalChromeRecorderBrowser implements RecorderBrowser {

    private static final Logger log = LoggerFactory.getLogger(LocalChromeRecorderBrowser.class);

    private final ChromeRecorderSettings settings;
    private final Clock clock;

    public LocalChromeRecorderBrowser(ChromeRecorderSettings settings, Clock clock) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public boolean isAvailable() {
        return settings.resolveBinary().isPresent();
    }

    @Override
    public String describe() {
        return settings.resolveBinary()
                .map(path -> "local-chrome (" + path.getFileName() + ")")
                .orElse("local-chrome (no Chrome or Chromium found on this machine)");
    }

    @Override
    public RecorderBrowserHandle open(RecorderBrowserRequest request) {
        Objects.requireNonNull(request, "request");
        ChromeProcess process = ChromeProcess.launch(settings, request.policy(), request.viewport());
        try {
            return new ChromeSession(request, process);
        } catch (RuntimeException e) {
            process.close();
            throw new RecorderBrowserUnavailableException(
                    "The recording browser started but could not be driven: " + e.getMessage(), e);
        }
    }

    /** One attached page, its interception wiring and its screencast. */
    private final class ChromeSession implements RecorderBrowserHandle {

        private final RecorderSessionId sessionId;
        private final RecorderSessionPolicy policy;
        private final RecorderViewport viewport;
        private final RecorderBrowserListener listener;
        private final ChromeProcess process;
        private final ChromeDevToolsClient client;
        private final String targetSession;
        private final String mainFrameId;

        /** Requests seen but not yet complete, keyed by the DevTools request id. */
        private final Map<String, PendingExchange> exchanges = new ConcurrentHashMap<>();
        private final AtomicBoolean closed = new AtomicBoolean();
        /**
         * Which page the requests arriving now belong to.
         *
         * <p>A browser recording is a sequence of page visits, and each visit is the closest thing
         * the session has to a statement of intent — the same signal a HAR's {@code pageref}
         * carries. Grouping by it is what makes a recording become several journeys rather than one
         * flat list of two hundred requests.
         */
        private final AtomicInteger journey = new AtomicInteger(1);
        private final ExecutorService captures = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "recorder-capture");
            thread.setDaemon(true);
            return thread;
        });
        /**
         * The browser's own deadline, independent of the control plane.
         *
         * <p>The orchestration layer already expires idle sessions, but only while it is running. A
         * control plane that is killed rather than shut down never closes anything, and a headless
         * Chrome holding a live corporate session would then sit in the process table indefinitely.
         * This bounds that to the session deadline whatever happens above.
         */
        private final ScheduledExecutorService watchdog =
                Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "recorder-watchdog");
                    thread.setDaemon(true);
                    return thread;
                });

        private ChromeSession(RecorderBrowserRequest request, ChromeProcess process) {
            this.sessionId = request.sessionId();
            this.policy = request.policy();
            this.viewport = request.viewport();
            this.listener = request.listener();
            this.process = process;
            this.client = ChromeDevToolsClient.connect(process.debuggerEndpoint(),
                    settings.startupTimeout(), settings.commandTimeout());

            try {
                this.targetSession = attachToNewPage();
                enableDomains();
                this.mainFrameId = readMainFrameId();
                client.onEvent(this::onProtocolEvent);
                client.onClose(reason -> end("recorder.browser.disconnected", reason));
                startScreencast();
                navigate(request.startUrl());
                watchdog.schedule(
                        () -> end("recorder.browser.deadline",
                                "The recording browser reached its own deadline and closed itself."),
                        request.deadline().toSeconds(), TimeUnit.SECONDS);
            } catch (RuntimeException e) {
                client.close();
                throw e;
            }
        }

        // ------------------------------------------------------------------ setup

        private String attachToNewPage() {
            // No width/height here: Chrome only accepts a target size when it is also asked to
            // open a new window. The recorded surface is sized by Emulation.setDeviceMetricsOverride
            // below, which is what the screencast follows anyway.
            JsonNode target = client.send("Target.createTarget",
                    Map.of("url", "about:blank"));
            String targetId = target.path("targetId").asText();
            // flatten:true puts every target's traffic on this one socket, tagged by sessionId,
            // rather than requiring a second connection per page.
            JsonNode attached = client.send("Target.attachToTarget",
                    Map.of("targetId", targetId, "flatten", Boolean.TRUE));
            return attached.path("sessionId").asText();
        }

        private void enableDomains() {
            client.send("Page.enable", Map.of(), targetSession);
            client.send("Network.enable", Map.of(), targetSession);
            client.send("Emulation.setDeviceMetricsOverride",
                    Map.of("width", viewport.width(),
                            "height", viewport.height(),
                            "deviceScaleFactor", 1,
                            "mobile", Boolean.FALSE),
                    targetSession);
            // Request stage only. Pausing responses as well would double the interception traffic
            // and buy nothing: the routing decision is made before the request leaves.
            client.send("Fetch.enable",
                    Map.of("patterns", java.util.List.of(
                            Map.of("urlPattern", "*", "requestStage", "Request"))),
                    targetSession);
        }

        private String readMainFrameId() {
            JsonNode tree = client.send("Page.getFrameTree", Map.of(), targetSession);
            return tree.path("frameTree").path("frame").path("id").asText(null);
        }

        private void startScreencast() {
            client.send("Page.startScreencast",
                    Map.of("format", "jpeg",
                            "quality", settings.frameQuality(),
                            "maxWidth", viewport.width(),
                            "maxHeight", viewport.height(),
                            "everyNthFrame", 1),
                    targetSession);
        }

        private void navigate(String url) {
            client.send("Page.navigate", Map.of("url", url), targetSession);
        }

        // ------------------------------------------------------------- protocol

        private void onProtocolEvent(ChromeDevToolsClient.CdpEvent event) {
            if (closed.get()) {
                return;
            }
            switch (event.method()) {
                case "Fetch.requestPaused" -> route(event.params());
                case "Page.screencastFrame" -> frame(event.params());
                case "Network.requestWillBeSent" -> rememberRequest(event.params());
                case "Network.responseReceived" -> rememberResponse(event.params());
                case "Network.loadingFinished" -> captures.execute(
                        () -> completeExchange(event.params().path("requestId").asText()));
                case "Network.loadingFailed" -> exchanges.remove(
                        event.params().path("requestId").asText());
                case "Page.frameNavigated" -> onFrameNavigated(event.params());
                case "Inspector.targetCrashed" -> end("recorder.browser.failed.crashed",
                        "The recording browser crashed.");
                case "Inspector.detached" -> end("recorder.browser.disconnected",
                        "The recording browser detached.");
                default -> {
                    // The protocol emits far more than this adapter acts on, by design.
                }
            }
        }

        /**
         * Decides one paused request.
         *
         * <p>Runs on the WebSocket listener thread and issues its decision without waiting for a
         * reply. A blocking call here would stall every other request behind it, which the user
         * experiences as a page that never finishes loading.
         */
        private void route(JsonNode params) {
            String requestId = params.path("requestId").asText();
            String url = params.path("request").path("url").asText("");
            RecorderRequestKind kind = ChromeResourceTypes.toRequestKind(
                    params.path("resourceType").asText(null),
                    params.path("frameId").asText(null),
                    mainFrameId);

            RecorderDecision decision = RecorderRouteGuard.evaluate(policy, url, kind);
            if (decision.allowed()) {
                client.fire("Fetch.continueRequest", Map.of("requestId", requestId), targetSession);
                return;
            }
            client.fire("Fetch.failRequest",
                    Map.of("requestId", requestId, "errorReason", "AccessDenied"), targetSession);
            listener.onBlocked(url, kind, decision, clock.instant());
        }

        /** A main-frame navigation starts a new candidate journey; an iframe does not. */
        private void onFrameNavigated(JsonNode params) {
            JsonNode frame = params.path("frame");
            if (frame.path("parentId").isMissingNode() || frame.path("parentId").asText("").isEmpty()) {
                journey.incrementAndGet();
            }
        }

        private void frame(JsonNode params) {
            String data = params.path("data").asText(null);
            String ackSession = params.path("sessionId").asText(null);
            if (ackSession != null) {
                // Unacknowledged frames stop the screencast after a few, so this must happen even
                // when the frame itself is unusable.
                client.fire("Page.screencastFrameAck",
                        Map.of("sessionId", params.path("sessionId").asInt()), targetSession);
            }
            if (data == null || data.isBlank()) {
                return;
            }
            JsonNode metadata = params.path("metadata");
            RecorderViewport frameViewport = metadata.hasNonNull("deviceWidth")
                    ? RecorderViewport.clamped(metadata.path("deviceWidth").asInt(),
                            metadata.path("deviceHeight").asInt())
                    : viewport;
            listener.onFrame(data, frameViewport, clock.instant());
        }

        // -------------------------------------------------------------- capture

        private void rememberRequest(JsonNode params) {
            if (!ChromeResourceTypes.isWorthCapturing(params.path("type").asText(null))) {
                return;
            }
            JsonNode request = params.path("request");
            exchanges.put(params.path("requestId").asText(), new PendingExchange(
                    request.path("method").asText("GET"),
                    request.path("url").asText(""),
                    headers(request.path("headers")),
                    request.path("postData").asText(null),
                    // Recorded when the request starts, not when it finishes: a slow call must not
                    // be filed under whichever page happened to be current by the time it returned.
                    "page-" + journey.get()));
        }

        private void rememberResponse(JsonNode params) {
            PendingExchange exchange = exchanges.get(params.path("requestId").asText());
            if (exchange == null) {
                return;
            }
            JsonNode response = params.path("response");
            exchange.responseHeaders = headers(response.path("headers"));
            exchange.responseContentType = response.path("mimeType").asText(null);
            exchange.status = response.path("status").asInt(0);
        }

        /**
         * Finishes one exchange and hands it up for sanitizing.
         *
         * <p>Runs on the capture executor, not the listener thread, because reading a body is a
         * round trip to the browser. The body is fetched only when it is textual and small: a
         * recorder holding a multi-megabyte response in memory for every request is how a session
         * exhausts its pod, and a binary body is of no use to a protocol journey anyway.
         */
        private void completeExchange(String requestId) {
            PendingExchange exchange = exchanges.remove(requestId);
            if (exchange == null || closed.get()) {
                return;
            }
            String body = null;
            if (ChromeResourceTypes.isTextualContent(exchange.responseContentType)) {
                body = readBody(requestId);
            }
            listener.onCapture(new RecorderCapture(
                    exchange.method, exchange.url, exchange.requestHeaders,
                    exchange.requestHeaders.get("content-type"), exchange.requestBody,
                    exchange.responseHeaders, exchange.responseContentType, body,
                    null, exchange.status, exchange.journeyRef), clock.instant());
        }

        private String readBody(String requestId) {
            try {
                JsonNode result = client.send("Network.getResponseBody",
                        Map.of("requestId", requestId), targetSession);
                if (result.path("base64Encoded").asBoolean(false)) {
                    return null;
                }
                String body = result.path("body").asText(null);
                return body != null && body.length() > settings.maxCaptureBytes() ? null : body;
            } catch (RuntimeException e) {
                // Bodies are evicted quickly and a redirect has none at all. A capture without a
                // body is still a useful step; failing the exchange would not be.
                log.debug("Recorder could not read a response body: {}", e.getMessage());
                return null;
            }
        }

        private static Map<String, String> headers(JsonNode node) {
            Map<String, String> headers = new LinkedHashMap<>();
            node.fields().forEachRemaining(entry ->
                    headers.put(entry.getKey().toLowerCase(java.util.Locale.ROOT),
                            entry.getValue().asText("")));
            return headers;
        }

        // ---------------------------------------------------------------- input

        @Override
        public void dispatch(RecorderInputEvent event) {
            if (closed.get() || !process.isAlive()) {
                throw new IllegalStateException("The recording browser has ended.");
            }
            if (event.kind() == RecorderInputKind.NAVIGATE_BACK) {
                navigateBack();
                return;
            }
            ChromeInputCommands.translate(event).ifPresent(call ->
                    client.fire(call.method(), call.params(), targetSession));
        }

        /**
         * Steps back one entry in the page's own history.
         *
         * <p>Expressed as a history lookup rather than as {@code history.back()} because evaluating
         * JavaScript in the page would be the one place a brokered input turns into script
         * execution, which is exactly what the bridge exists to prevent.
         */
        private void navigateBack() {
            JsonNode history = client.send("Page.getNavigationHistory", Map.of(), targetSession);
            int current = history.path("currentIndex").asInt(0);
            if (current <= 0) {
                return;
            }
            int entryId = history.path("entries").path(current - 1).path("id").asInt(-1);
            if (entryId >= 0) {
                client.fire("Page.navigateToHistoryEntry", Map.of("entryId", entryId),
                        targetSession);
            }
        }

        // ------------------------------------------------------------ lifecycle

        @Override
        public RecorderSessionId sessionId() {
            return sessionId;
        }

        @Override
        public boolean isAlive() {
            return !closed.get() && process.isAlive() && !client.isClosed();
        }

        @Override
        public void close() {
            end("recorder.browser.closed", "The recording session ended.");
        }

        /** Idempotent: the browser can end from a user stop, a crash or a dropped socket at once. */
        private void end(String code, String reason) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            Instant endedAt = clock.instant();
            watchdog.shutdownNow();
            captures.shutdownNow();
            try {
                captures.awaitTermination(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchanges.clear();
            client.close();
            process.close();
            listener.onClosed(code, reason, endedAt);
        }
    }

    /** Request material held between {@code requestWillBeSent} and {@code loadingFinished}. */
    private static final class PendingExchange {
        private final String method;
        private final String url;
        private final Map<String, String> requestHeaders;
        private final String requestBody;
        private final String journeyRef;
        private volatile Map<String, String> responseHeaders = Map.of();
        private volatile String responseContentType;
        private volatile int status;

        private PendingExchange(String method, String url, Map<String, String> requestHeaders,
                String requestBody, String journeyRef) {
            this.method = method;
            this.url = url;
            this.requestHeaders = requestHeaders;
            this.requestBody = requestBody;
            this.journeyRef = journeyRef;
        }
    }
}
