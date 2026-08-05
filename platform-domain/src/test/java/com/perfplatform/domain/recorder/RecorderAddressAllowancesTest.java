package com.perfplatform.domain.recorder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RecorderAddressAllowancesTest {

    @Test
    @DisplayName("the default floor still denies loopback")
    void strictByDefault() {
        assertThat(RecorderAddressAllowances.strict().loopbackPermitted()).isFalse();
        assertThat(RecorderAddressPolicy.check("localhost", List.of(ip("127.0.0.1")), List.of())
                .allowed()).isFalse();
    }

    @Test
    @DisplayName("the local-development allowance permits loopback and nothing else")
    void loopbackAllowanceIsNarrow() {
        RecorderAddressAllowances allowances = RecorderAddressAllowances.permittingLoopback();

        assertThat(RecorderAddressPolicy.check("localhost", List.of(ip("127.0.0.1")), List.of(),
                allowances).allowed()).isTrue();

        // Everything else in the floor is untouched by the bypass.
        assertThat(RecorderAddressPolicy.check("metadata.example", List.of(ip("169.254.169.254")),
                List.of(), allowances).code()).isEqualTo("recorder.address.metadata");
        assertThat(RecorderAddressPolicy.check("linklocal.example", List.of(ip("169.254.10.10")),
                List.of(), allowances).code()).isEqualTo("recorder.address.linkLocal");
        assertThat(RecorderAddressPolicy.check("wild.example", List.of(ip("0.0.0.0")),
                List.of(), allowances).code()).isEqualTo("recorder.address.wildcard");
        assertThat(RecorderAddressPolicy.check("multi.example", List.of(ip("239.1.2.3")),
                List.of(), allowances).code()).isEqualTo("recorder.address.multicast");
        assertThat(RecorderAddressPolicy.check("banned.example", List.of(ip("10.9.8.7")),
                List.of(CidrBlock.parse("10.9.0.0/16")), allowances).code())
                .isEqualTo("recorder.address.prohibitedRange");
    }

    @Test
    @DisplayName("a session policy carries its allowances into the route guard")
    void routeGuardHonoursSessionAllowances() {
        RecorderSessionPolicy policy = RecorderSessionPolicy
                .forTarget(RecorderOrigin.parse("http://localhost:8081"), List.of(ip("127.0.0.1")))
                .withAllowances(RecorderAddressAllowances.permittingLoopback())
                .build();

        assertThat(policy.allowances().isRelaxed()).isTrue();
        assertThat(RecorderRouteGuard.evaluate(policy, "http://localhost:8081/orders",
                RecorderRequestKind.TOP_LEVEL_NAVIGATION).allowed()).isTrue();
    }

    @Test
    @DisplayName("a relaxed session still refuses destinations outside its allowlist")
    void allowlistStillApplies() {
        RecorderSessionPolicy policy = RecorderSessionPolicy
                .forTarget(RecorderOrigin.parse("http://localhost:8081"), List.of(ip("127.0.0.1")))
                .withAllowances(RecorderAddressAllowances.permittingLoopback())
                .build();

        assertThat(RecorderRouteGuard.evaluate(policy, "http://localhost:9999/other",
                RecorderRequestKind.FETCH).code())
                .isEqualTo("recorder.destination.outsideAllowlist");
    }

    private static InetAddress ip(String literal) {
        try {
            return InetAddress.getByName(literal);
        } catch (java.net.UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }
}
