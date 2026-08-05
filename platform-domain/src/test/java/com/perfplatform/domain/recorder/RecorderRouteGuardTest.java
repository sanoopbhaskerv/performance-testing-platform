package com.perfplatform.domain.recorder;

import com.perfplatform.domain.identity.ApplicationId;
import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.spec.TargetClass;
import com.perfplatform.domain.target.RegisteredTarget;
import com.perfplatform.domain.target.TargetAddress;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecorderRouteGuardTest {

    @Test
    @DisplayName("the registered target origin is allowed")
    void targetAllowed() throws Exception {
        RecorderDecision decision = RecorderRouteGuard.evaluate(policy(),
                "https://proposal.perf.internal/workflow/start?client=123",
                RecorderRequestKind.TOP_LEVEL_NAVIGATION);

        assertThat(decision.allowed()).isTrue();
    }

    @Test
    @DisplayName("declared asset and login origins are allowed")
    void declaredOriginsAllowed() throws Exception {
        RecorderSessionPolicy policy = RecorderSessionPolicy.forTarget(target(), List.of(ip("10.10.20.30")))
                .allowAssetOrigin("https://static.perf.internal", List.of(ip("10.10.20.31")))
                .allowLoginOrigin("https://sso.perf.internal", List.of(ip("10.10.20.32")))
                .build();

        assertThat(RecorderRouteGuard.evaluate(policy, "https://static.perf.internal/app.js",
                RecorderRequestKind.SUBRESOURCE).allowed()).isTrue();
        assertThat(RecorderRouteGuard.evaluate(policy, "https://sso.perf.internal/login",
                RecorderRequestKind.IFRAME).allowed()).isTrue();
    }

    @Test
    @DisplayName("redirects and subresources outside the session allowlist are blocked")
    void outsideAllowlistBlocked() throws Exception {
        RecorderDecision redirect = RecorderRouteGuard.evaluate(policy(),
                "https://admin.perf.internal/console", RecorderRequestKind.TOP_LEVEL_NAVIGATION);
        RecorderDecision subresource = RecorderRouteGuard.evaluate(policy(),
                "https://other-team.perf.internal/app.js", RecorderRequestKind.SUBRESOURCE);

        assertThat(redirect.allowed()).isFalse();
        assertThat(redirect.code()).isEqualTo("recorder.destination.outsideAllowlist");
        assertThat(subresource.allowed()).isFalse();
        assertThat(subresource.code()).isEqualTo("recorder.destination.outsideAllowlist");
    }

    @Test
    @DisplayName("popups, workers, fetch, WebSocket and EventSource use the same allowlist")
    void everyRequestSurfaceIsGated() throws Exception {
        RecorderSessionPolicy policy = policy();

        assertBlocked(policy, "https://other-team.perf.internal/popup", RecorderRequestKind.POPUP);
        assertBlocked(policy, "https://other-team.perf.internal/frame", RecorderRequestKind.IFRAME);
        assertBlocked(policy, "https://other-team.perf.internal/worker.js", RecorderRequestKind.WORKER);
        assertBlocked(policy, "https://other-team.perf.internal/api", RecorderRequestKind.FETCH);
        assertBlocked(policy, "wss://other-team.perf.internal/socket", RecorderRequestKind.WEBSOCKET);
        assertBlocked(policy, "https://other-team.perf.internal/events", RecorderRequestKind.EVENT_SOURCE);
    }

    @Test
    @DisplayName("WebSockets to an allowed origin are allowed")
    void websocketAllowed() throws Exception {
        RecorderDecision decision = RecorderRouteGuard.evaluate(policy(),
                "wss://proposal.perf.internal/socket", RecorderRequestKind.WEBSOCKET);

        assertThat(decision.allowed()).isTrue();
    }

    @Test
    @DisplayName("raw IP navigation is blocked unless that exact IP is the registered target")
    void rawIpBlockedUnlessRegisteredTarget() throws Exception {
        RecorderDecision blocked = RecorderRouteGuard.evaluate(policy(),
                "https://10.10.20.30/", RecorderRequestKind.TOP_LEVEL_NAVIGATION);
        RecorderSessionPolicy rawTarget = RecorderSessionPolicy.forTarget(
                RecorderOrigin.parse("https://10.10.20.30"), List.of(ip("10.10.20.30"))).build();
        RecorderDecision allowed = RecorderRouteGuard.evaluate(rawTarget,
                "https://10.10.20.30/orders", RecorderRequestKind.TOP_LEVEL_NAVIGATION);

        assertThat(blocked.allowed()).isFalse();
        assertThat(blocked.code()).isEqualTo("recorder.destination.rawIp");
        assertThat(allowed.allowed()).isTrue();
    }

    @Test
    @DisplayName("top-level file, data and blob navigation is blocked")
    void localSchemesBlocked() throws Exception {
        RecorderSessionPolicy policy = policy();

        assertThat(RecorderRouteGuard.evaluate(policy, "file:///etc/passwd",
                RecorderRequestKind.TOP_LEVEL_NAVIGATION).code())
                .isEqualTo("recorder.destination.schemeBlocked");
        assertThat(RecorderRouteGuard.evaluate(policy, "data:text/plain,hello",
                RecorderRequestKind.TOP_LEVEL_NAVIGATION).code())
                .isEqualTo("recorder.destination.schemeBlocked");
        assertThat(RecorderRouteGuard.evaluate(policy, "blob:https://proposal.perf.internal/123",
                RecorderRequestKind.TOP_LEVEL_NAVIGATION).code())
                .isEqualTo("recorder.destination.schemeBlocked");
    }

    @Test
    @DisplayName("URLs carrying embedded credentials are blocked")
    void credentialsInUrlBlocked() throws Exception {
        RecorderDecision decision = RecorderRouteGuard.evaluate(policy(),
                "https://user:password@proposal.perf.internal/orders",
                RecorderRequestKind.TOP_LEVEL_NAVIGATION);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.code()).isEqualTo("recorder.destination.credentialsInUrl");
    }

    @Test
    @DisplayName("allowed origins resolving into prohibited ranges make the policy invalid")
    void prohibitedAllowedOriginRejected() throws Exception {
        assertThatThrownBy(() -> RecorderSessionPolicy.forTarget(target(), List.of(ip("10.96.0.1")))
                .prohibit(CidrBlock.parse("10.96.0.0/12"))
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("prohibited range");
    }

    @Test
    @DisplayName("raw IP asset origins are rejected even if someone tries to add one")
    void rawIpAssetRejected() throws Exception {
        assertThatThrownBy(() -> RecorderSessionPolicy.forTarget(target(), List.of(ip("10.10.20.30")))
                .allowAssetOrigin("https://10.10.20.31", List.of(ip("10.10.20.31"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Raw IP origins");
    }

    private static void assertBlocked(RecorderSessionPolicy policy, String destination,
            RecorderRequestKind kind) {
        RecorderDecision decision = RecorderRouteGuard.evaluate(policy, destination, kind);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.code()).isEqualTo("recorder.destination.outsideAllowlist");
    }

    private static RecorderSessionPolicy policy() throws Exception {
        return RecorderSessionPolicy.forTarget(target(), List.of(ip("10.10.20.30"))).build();
    }

    private static RegisteredTarget target() {
        return new RegisteredTarget(TeamId.generate(), ApplicationId.generate(),
                "proposal", "performance", TargetClass.NON_PRODUCTION,
                TargetAddress.parse("https://proposal.perf.internal"),
                "test", Instant.parse("2026-08-05T00:00:00Z"));
    }

    private static InetAddress ip(String value) throws Exception {
        return InetAddress.getByName(value);
    }
}
