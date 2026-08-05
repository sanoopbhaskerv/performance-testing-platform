package com.perfplatform.domain.recorder;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Browser-layer recorder egress decision.
 *
 * <p>This is not the only control: a NetworkPolicy or egress proxy must enforce the same frozen
 * destinations outside the browser. This class is still required because it gives immediate,
 * auditable feedback for navigations, redirects, iframes, workers and WebSockets that the user or
 * page attempted.
 */
public final class RecorderRouteGuard {

    private RecorderRouteGuard() {
    }

    public static RecorderDecision evaluate(RecorderSessionPolicy policy, String destination,
            RecorderRequestKind kind) {

        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(kind, "kind");

        URI uri;
        try {
            uri = RecorderOrigin.parseUri(destination);
        } catch (IllegalArgumentException e) {
            return RecorderDecision.block("recorder.destination.invalid", e.getMessage());
        }

        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (uri.getUserInfo() != null && !uri.getUserInfo().isBlank()) {
            return RecorderDecision.block("recorder.destination.credentialsInUrl",
                    "Recorder URLs must not contain embedded credentials.");
        }
        if (kind == RecorderRequestKind.TOP_LEVEL_NAVIGATION
                && ("file".equals(scheme) || "data".equals(scheme) || "blob".equals(scheme))) {
            return RecorderDecision.block("recorder.destination.schemeBlocked",
                    "Local, data and blob top-level navigation is blocked for recorder sessions.");
        }
        if (kind != RecorderRequestKind.WEBSOCKET && ("ws".equals(scheme) || "wss".equals(scheme))) {
            return RecorderDecision.block("recorder.destination.schemeBlocked",
                    "WebSocket URLs are allowed only for WebSocket requests.");
        }

        RecorderOrigin origin;
        try {
            origin = RecorderOrigin.fromUri(uri, kind);
        } catch (IllegalArgumentException e) {
            return RecorderDecision.block("recorder.destination.schemeBlocked", e.getMessage());
        }

        if (origin.isIpLiteral() && !policy.isRegisteredTargetOrigin(origin)) {
            return RecorderDecision.block("recorder.destination.rawIp",
                    "Raw IP destinations are allowed only when the registered target is that exact origin.");
        }

        Optional<RecorderSessionPolicy.AllowedOrigin> allowed = policy.find(origin);
        if (allowed.isEmpty()) {
            return RecorderDecision.block("recorder.destination.outsideAllowlist",
                    "Destination origin " + origin.value() + " is not in this recorder session's allowlist.");
        }

        RecorderDecision addressDecision = RecorderAddressPolicy.check(
                origin.host(), allowed.get().resolvedAddresses(), policy.prohibitedRanges(),
                policy.allowances());
        if (!addressDecision.allowed()) {
            return addressDecision;
        }

        return RecorderDecision.allow();
    }
}
