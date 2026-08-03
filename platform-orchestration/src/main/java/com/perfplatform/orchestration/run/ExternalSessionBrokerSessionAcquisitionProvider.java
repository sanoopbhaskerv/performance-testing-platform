package com.perfplatform.orchestration.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.perfplatform.domain.port.SecretResolver;
import com.perfplatform.domain.port.SessionAcquisitionProvider;
import com.perfplatform.domain.port.SessionPoolProvider;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Production session acquisition adapter for an approved external session broker.
 *
 * <p>The broker owns application-specific login and refresh flows. The control plane sends a
 * bounded, auditable request and receives session leases to partition into runner-mounted files.
 * Its own broker credential is resolved through {@link SecretResolver}; no session value or client
 * secret is accepted through runtime trigger input or deployment values.
 */
public final class ExternalSessionBrokerSessionAcquisitionProvider
        implements SessionAcquisitionProvider {

    public static final String PROVIDER_ID = "external-session-broker";
    public static final String DEFAULT_ACQUIRE_PATH = "/v1/sessions:acquire";
    public static final String DEFAULT_REFRESH_PATH = "/v1/sessions:refresh";

    private final HttpClient http;
    private final ObjectMapper json;
    private final URI endpoint;
    private final String acquirePath;
    private final String refreshPath;
    private final String clientCredentialSecretRef;
    private final SecretResolver secrets;
    private final Duration requestTimeout;

    public ExternalSessionBrokerSessionAcquisitionProvider(HttpClient http, URI endpoint,
            String acquirePath, String refreshPath, String clientCredentialSecretRef,
            SecretResolver secrets, Duration requestTimeout) {

        this.http = Objects.requireNonNull(http, "http");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.acquirePath = defaultPath(acquirePath, DEFAULT_ACQUIRE_PATH);
        this.refreshPath = defaultPath(refreshPath, DEFAULT_REFRESH_PATH);
        this.clientCredentialSecretRef = requireText(clientCredentialSecretRef,
                "clientCredentialSecretRef");
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        if (requestTimeout.isNegative() || requestTimeout.isZero()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
        this.json = new ObjectMapper();
    }

    @Override
    public SessionPoolProvider.SessionLease acquire(SessionAcquisitionRequest request) {
        ObjectNode body = commonBody("acquire", request.runId().value().toString(),
                request.authenticationName(), request.requestedAt(), request.minimumValidUntil());
        body.put("sessionIndex", request.sessionIndex());
        return send(brokerUri(acquirePath), body, request.minimumValidUntil());
    }

    @Override
    public SessionPoolProvider.SessionLease refresh(SessionRefreshRequest request) {
        ObjectNode body = commonBody("refresh", request.runId().value().toString(),
                request.authenticationName(), request.requestedAt(), request.minimumValidUntil());
        body.put("currentSessionId", request.current().sessionId());
        return send(brokerUri(refreshPath), body, request.minimumValidUntil());
    }

    private ObjectNode commonBody(String operation, String runId, String authenticationName,
            Instant requestedAt, Instant minimumValidUntil) {
        ObjectNode body = json.createObjectNode();
        body.put("operation", operation);
        body.put("runId", runId);
        body.put("authenticationName", authenticationName);
        body.put("requestedAt", requestedAt.toString());
        body.put("minimumValidUntil", minimumValidUntil.toString());
        return body;
    }

    private SessionPoolProvider.SessionLease send(URI uri, ObjectNode body,
            Instant minimumValidUntil) {
        SecretResolver.SecretMaterial credential = secrets.resolve(clientCredentialSecretRef);
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(uri)
                    .timeout(requestTimeout)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + credential.value())
                    .header("X-Performance-Test-Run-Id", body.get("runId").asText())
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                    .build();
        } catch (IOException e) {
            throw new SessionAcquisitionException("Could not serialize session broker request", e);
        }

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SessionAcquisitionException("Interrupted while calling session broker", e);
        } catch (IOException e) {
            throw new SessionAcquisitionException("Session broker request failed", e);
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new SessionAcquisitionException(
                    "Session broker rejected acquisition request with status "
                            + response.statusCode());
        }

        return parseLease(response.body(), minimumValidUntil);
    }

    private SessionPoolProvider.SessionLease parseLease(String responseBody,
            Instant minimumValidUntil) {
        JsonNode root;
        try {
            root = json.readTree(responseBody);
        } catch (IOException e) {
            throw new SessionAcquisitionException("Session broker returned invalid JSON", e);
        }

        String sessionId = requiredText(root, "sessionId");
        String sessionValue = requiredText(root, "sessionValue");
        Instant expiresAt;
        try {
            expiresAt = Instant.parse(requiredText(root, "expiresAt"));
        } catch (RuntimeException e) {
            throw new SessionAcquisitionException(
                    "Session broker returned an invalid expiresAt timestamp", e);
        }
        if (expiresAt.isBefore(minimumValidUntil)) {
            throw new SessionAcquisitionException(
                    "Session broker returned a lease that expires before the requested validity window");
        }

        return new SessionPoolProvider.SessionLease(sessionId, sessionValue, expiresAt);
    }

    private URI brokerUri(String path) {
        String base = endpoint.toString();
        if (!base.endsWith("/")) {
            base = base + "/";
        }
        String relative = path.startsWith("/") ? path.substring(1) : path;
        return URI.create(base).resolve(relative);
    }

    private static String requiredText(JsonNode root, String field) {
        JsonNode node = root == null ? null : root.get(field);
        if (node == null || node.asText().isBlank()) {
            throw new SessionAcquisitionException(
                    "Session broker response is missing required field '" + field + "'");
        }
        return node.asText();
    }

    private static String defaultPath(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    /** Raised for broker acquisition failures. Messages are intentionally sanitized. */
    public static class SessionAcquisitionException extends RuntimeException {
        public SessionAcquisitionException(String message) {
            super(message);
        }

        public SessionAcquisitionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
