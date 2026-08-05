package com.perfplatform.runner.chrome;

import com.perfplatform.domain.identity.RecorderSessionId;
import com.perfplatform.domain.port.RecorderBrowser.RecorderBrowserHandle;
import com.perfplatform.domain.port.RecorderBrowser.RecorderBrowserRequest;
import com.perfplatform.domain.port.RecorderBrowserListener;
import com.perfplatform.domain.recorder.RecorderAddressAllowances;
import com.perfplatform.domain.recorder.RecorderCapture;
import com.perfplatform.domain.recorder.RecorderDecision;
import com.perfplatform.domain.recorder.RecorderInputEvent;
import com.perfplatform.domain.recorder.RecorderOrigin;
import com.perfplatform.domain.recorder.RecorderRequestKind;
import com.perfplatform.domain.recorder.RecorderSessionPolicy;
import com.perfplatform.domain.recorder.RecorderViewport;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives a real Chrome against a real HTTP server.
 *
 * <p>Opt-in, because it needs a browser installed and takes seconds rather than milliseconds:
 *
 * <pre>{@code
 * mvn -pl runners/runner-chrome -am test -Drecorder.live=true
 * }</pre>
 *
 * <p>Everything else in this module tests the pieces in isolation, which is the right default. This
 * is the one test that answers the question those cannot: does the protocol wiring — attach,
 * intercept, screencast, capture — actually work against the browser as shipped. Without it the
 * adapter is only asserted, never exercised.
 */
@EnabledIfSystemProperty(named = "recorder.live", matches = "true")
class LocalChromeRecorderBrowserLiveTest {

    private HttpServer server;
    private int port;

    @BeforeEach
    void startTarget() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = """
                    <html><body><h1>Recorder target</h1>
                    <script>fetch('/api/orders').then(r => r.json());</script>
                    </body></html>""";
            respond(exchange, "text/html", body);
        });
        server.createContext("/api/orders", exchange ->
                respond(exchange, "application/json", "{\"orders\":[{\"id\":1}]}"));
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stopTarget() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("a real browser streams frames, captures the API call and refuses an unlisted origin")
    void liveSession() throws Exception {
        LocalChromeRecorderBrowser browser = new LocalChromeRecorderBrowser(
                ChromeRecorderSettings.defaults(), Clock.systemUTC());
        assertThat(browser.isAvailable())
                .as("this test needs Chrome or Chromium installed")
                .isTrue();

        RecordingListener listener = new RecordingListener();
        RecorderBrowserHandle handle = browser.open(new RecorderBrowserRequest(
                RecorderSessionId.generate(), policy(), RecorderViewport.DEFAULT,
                "http://localhost:" + port + "/", Duration.ofMinutes(1), Duration.ofMinutes(2),
                listener));

        try {
            assertThat(listener.frames.await(30, TimeUnit.SECONDS))
                    .as("the screencast should produce frames")
                    .isTrue();
            assertThat(listener.captured.await(30, TimeUnit.SECONDS))
                    .as("the page's own fetch should be captured")
                    .isTrue();

            assertThat(listener.captures)
                    .anySatisfy(capture -> assertThat(capture.url()).endsWith("/api/orders"));

            assertThat(listener.captures)
                    .as("static assets should not be captured")
                    .noneSatisfy(capture -> assertThat(capture.url()).contains(".png"));

            handle.dispatch(RecorderInputEvent.text("hello"));

            // The browser-layer route guard, exercised directly. The use case refuses this
            // navigation before it ever reaches a browser; dispatching it here is the only way to
            // prove the second, independent control actually fires.
            assertThat(listener.blocked).isEmpty();

            // A raw IP is refused by the raw-IP rule, which is checked before allowlist membership
            // — a browsing session may not address hosts numerically at all unless the registered
            // target itself is that exact IP origin.
            handle.dispatch(RecorderInputEvent.navigate("http://127.0.0.1:1/nope"));
            assertThat(listener.blockedSomething.await(30, TimeUnit.SECONDS))
                    .as("an unlisted origin should be refused by the route guard")
                    .isTrue();
            assertThat(listener.blocked).first().asString()
                    .contains("recorder.destination.rawIp");

            // A named origin outside the allowlist is refused by the allowlist rule. It is refused
            // by the guard, not merely unresolvable: the request is paused before DNS.
            handle.dispatch(RecorderInputEvent.navigate("http://elsewhere.example/nope"));
            waitUntil(() -> listener.blocked.stream()
                    .anyMatch(entry -> entry.contains("recorder.destination.outsideAllowlist")));
        } finally {
            handle.close();
        }

        assertThat(listener.closed.await(20, TimeUnit.SECONDS)).isTrue();
        assertThat(handle.isAlive()).isFalse();
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(30);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("The expected recorder event did not arrive within 30 seconds");
    }

    private RecorderSessionPolicy policy() throws Exception {
        return RecorderSessionPolicy
                .forTarget(RecorderOrigin.parse("http://localhost:" + port),
                        List.of(InetAddress.getByName("127.0.0.1")))
                .withAllowances(RecorderAddressAllowances.permittingLoopback())
                .build();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String contentType,
            String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static final class RecordingListener implements RecorderBrowserListener {
        private final CountDownLatch frames = new CountDownLatch(1);
        private final CountDownLatch captured = new CountDownLatch(1);
        private final CountDownLatch closed = new CountDownLatch(1);
        private final CountDownLatch blockedSomething = new CountDownLatch(1);
        private final List<RecorderCapture> captures = new CopyOnWriteArrayList<>();
        private final List<String> blocked = new CopyOnWriteArrayList<>();

        @Override
        public void onFrame(String imageBase64, RecorderViewport viewport, Instant capturedAt) {
            frames.countDown();
        }

        @Override
        public void onBlocked(String destination, RecorderRequestKind kind,
                RecorderDecision decision, Instant blockedAt) {
            blocked.add(decision.code() + " " + destination);
            blockedSomething.countDown();
        }

        @Override
        public void onCapture(RecorderCapture capture, Instant capturedAt) {
            captures.add(capture);
            if (capture.url().endsWith("/api/orders")) {
                captured.countDown();
            }
        }

        @Override
        public void onClosed(String code, String reason, Instant closedAt) {
            closed.countDown();
        }
    }
}
