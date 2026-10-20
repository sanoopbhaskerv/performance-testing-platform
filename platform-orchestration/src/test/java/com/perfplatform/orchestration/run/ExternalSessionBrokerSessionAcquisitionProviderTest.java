package com.perfplatform.orchestration.run;

import com.perfplatform.domain.identity.TestRunId;
import com.perfplatform.domain.port.SecretResolver;
import com.perfplatform.domain.port.SessionAcquisitionProvider;
import com.perfplatform.domain.port.SessionPoolProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalSessionBrokerSessionAcquisitionProviderTest {

    private static final Instant NOW = Instant.parse("2026-08-03T10:00:00Z");
    private static final String SECRET_REF = "k8s://session-broker-client/token";
    private static final URI ENDPOINT = URI.create("https://session-broker.internal");

    @Test
    @DisplayName("acquire posts the broker contract and resolves the broker credential by reference")
    void acquireUsesBrokerContractAndSecretResolver() {
        CapturingHttpClient http = new CapturingHttpClient(response(200,
                """
                {
                  "sessionId": "broker-session-002",
                  "sessionValue": "Bearer broker-issued-session",
                  "expiresAt": "2026-08-03T11:00:00Z"
                }
                """));
        AtomicReference<String> resolved = new AtomicReference<>();
        var provider = provider(http, reference -> {
            resolved.set(reference);
            return new SecretResolver.SecretMaterial(reference, "broker-client-token");
        });

        SessionPoolProvider.SessionLease lease = provider.acquire(
                new SessionAcquisitionProvider.SessionAcquisitionRequest(
                        TestRunId.generate(), "session", 2, null, NOW,
                        NOW.plus(Duration.ofMinutes(30))));

        assertThat(resolved).hasValue(SECRET_REF);
        assertThat(lease.sessionId()).isEqualTo("broker-session-002");
        assertThat(lease.value()).isEqualTo("Bearer broker-issued-session");
        assertThat(lease.expiresAt()).isEqualTo(Instant.parse("2026-08-03T11:00:00Z"));
        assertThat(lease.toString()).doesNotContain("Bearer broker-issued-session");

        CapturedRequest request = http.requests().get(0);
        assertThat(request.path()).isEqualTo(
                ExternalSessionBrokerSessionAcquisitionProvider.DEFAULT_ACQUIRE_PATH);
        assertThat(request.authorization()).isEqualTo("Bearer broker-client-token");
        assertThat(request.body()).contains("\"operation\":\"acquire\"");
        assertThat(request.body()).contains("\"authenticationName\":\"session\"");
        assertThat(request.body()).contains("\"sessionIndex\":2");
        assertThat(request.body()).contains("\"minimumValidUntil\":\"2026-08-03T10:30:00Z\"");
        assertThat(request.body()).doesNotContain("broker-client-token");
    }

    @Test
    @DisplayName("refresh posts the current broker session id through the refresh contract")
    void refreshUsesBrokerContract() {
        CapturingHttpClient http = new CapturingHttpClient(response(200,
                """
                {
                  "sessionId": "broker-session-refreshed",
                  "sessionValue": "Bearer refreshed-session",
                  "expiresAt": "2026-08-03T12:00:00Z"
                }
                """));
        var provider = provider(http, "broker-client-token");

        SessionPoolProvider.SessionLease lease = provider.refresh(
                new SessionAcquisitionProvider.SessionRefreshRequest(
                        TestRunId.generate(), "session",
                        new SessionPoolProvider.SessionLease("broker-session-old",
                                "Bearer old-session", NOW.plus(Duration.ofMinutes(10))),
                        null, NOW, NOW.plus(Duration.ofMinutes(30))));

        assertThat(lease.sessionId()).isEqualTo("broker-session-refreshed");
        CapturedRequest request = http.requests().get(0);
        assertThat(request.path()).isEqualTo(
                ExternalSessionBrokerSessionAcquisitionProvider.DEFAULT_REFRESH_PATH);
        assertThat(request.body()).contains("\"operation\":\"refresh\"");
        assertThat(request.body()).contains("\"currentSessionId\":\"broker-session-old\"");
        assertThat(request.body()).doesNotContain("Bearer old-session");
    }

    @Test
    @DisplayName("a broker lease expiring before the requested window is refused")
    void refusesLeaseBeforeMinimumValidity() {
        CapturingHttpClient http = new CapturingHttpClient(response(200,
                """
                {
                  "sessionId": "too-short",
                  "sessionValue": "Bearer short-lived",
                  "expiresAt": "2026-08-03T10:05:00Z"
                }
                """));

        assertThatThrownBy(() -> provider(http, "broker-client-token").acquire(
                new SessionAcquisitionProvider.SessionAcquisitionRequest(
                        TestRunId.generate(), "session", 0, null, NOW,
                        NOW.plus(Duration.ofMinutes(30)))))
                .isInstanceOf(ExternalSessionBrokerSessionAcquisitionProvider
                        .SessionAcquisitionException.class)
                .hasMessageContaining("expires before the requested validity window")
                .hasMessageNotContaining("Bearer short-lived");
    }

    @Test
    @DisplayName("broker failures are sanitized")
    void brokerFailureIsSanitized() {
        CapturingHttpClient http = new CapturingHttpClient(response(500,
                "client=broker-client-token session=Bearer leaked-session"));

        assertThatThrownBy(() -> provider(http, "broker-client-token").acquire(
                new SessionAcquisitionProvider.SessionAcquisitionRequest(
                        TestRunId.generate(), "session", 0, null, NOW,
                        NOW.plus(Duration.ofMinutes(30)))))
                .isInstanceOf(ExternalSessionBrokerSessionAcquisitionProvider
                        .SessionAcquisitionException.class)
                .hasMessageContaining("status 500")
                .hasMessageNotContaining("broker-client-token")
                .hasMessageNotContaining("leaked-session");
    }

    private ExternalSessionBrokerSessionAcquisitionProvider provider(
            CapturingHttpClient http, String clientToken) {
        return provider(http,
                reference -> new SecretResolver.SecretMaterial(reference, clientToken));
    }

    private ExternalSessionBrokerSessionAcquisitionProvider provider(
            CapturingHttpClient http, SecretResolver resolver) {
        return new ExternalSessionBrokerSessionAcquisitionProvider(
                http, ENDPOINT, "", "", SECRET_REF, resolver, Duration.ofSeconds(2));
    }

    private static BrokerResponse response(int status, String body) {
        return new BrokerResponse(status, body);
    }

    private record BrokerResponse(int status, String body) {
    }

    private record CapturedRequest(String path, String authorization, String body) {
    }

    private static final class CapturingHttpClient extends HttpClient {
        private final BrokerResponse response;
        private final List<CapturedRequest> requests = new ArrayList<>();

        private CapturingHttpClient(BrokerResponse response) {
            this.response = response;
        }

        private List<CapturedRequest> requests() {
            return requests;
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler) {
            requests.add(new CapturedRequest(
                    request.uri().getPath(),
                    request.headers().firstValue("Authorization").orElse(""),
                    requestBody(request)));
            @SuppressWarnings("unchecked")
            T body = (T) response.body();
            return new BrokerHttpResponse<>(request, response.status(), body);
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler) {
            return CompletableFuture.completedFuture(send(request, responseBodyHandler));
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return CompletableFuture.completedFuture(send(request, responseBodyHandler));
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            return null;
        }

        @Override
        public SSLParameters sslParameters() {
            return null;
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }
    }

    private static final class BrokerHttpResponse<T> implements HttpResponse<T> {
        private final HttpRequest request;
        private final int status;
        private final T body;

        private BrokerHttpResponse(HttpRequest request, int status, T body) {
            this.request = request;
            this.status = status;
            this.body = body;
        }

        @Override
        public int statusCode() {
            return status;
        }

        @Override
        public HttpRequest request() {
            return request;
        }

        @Override
        public Optional<HttpResponse<T>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(java.util.Map.of(), (left, right) -> true);
        }

        @Override
        public T body() {
            return body;
        }

        @Override
        public Optional<javax.net.ssl.SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return request.uri();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }

    private static String requestBody(HttpRequest request) {
        HttpRequest.BodyPublisher publisher = request.bodyPublisher().orElseThrow();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CompletableFuture<Void> finished = new CompletableFuture<>();
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer item) {
                ByteBuffer copy = item.slice();
                byte[] bytes = new byte[copy.remaining()];
                copy.get(bytes);
                try {
                    out.write(bytes);
                } catch (IOException e) {
                    finished.completeExceptionally(e);
                }
            }

            @Override
            public void onError(Throwable throwable) {
                finished.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                finished.complete(null);
            }
        });
        finished.join();
        return out.toString(StandardCharsets.UTF_8);
    }
}
