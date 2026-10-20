package com.perfplatform.domain.recorder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The address floor for recorder sessions.
 *
 * <p>Unlike load-test targets, loopback is not a development convenience here. It would mean
 * "connect back into the recorder pod", which is not a team application and can expose pod-local
 * services.
 */
class RecorderAddressPolicyTest {

    @Test
    @DisplayName("approved private application addresses are allowed")
    void privateApplicationTargetIsAllowed() throws Exception {
        RecorderDecision decision = RecorderAddressPolicy.check(
                "proposal.perf.internal", List.of(ip("10.10.20.30")), List.of());

        assertThat(decision.allowed()).isTrue();
    }

    @Test
    @DisplayName("loopback is blocked for interactive recorder sessions")
    void loopbackBlocked() throws Exception {
        RecorderDecision decision = RecorderAddressPolicy.check(
                "localhost", List.of(ip("127.0.0.1")), List.of());

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.code()).isEqualTo("recorder.address.loopback");
    }

    @Test
    @DisplayName("cloud metadata endpoints are blocked by literal address")
    void metadataLiteralBlocked() throws Exception {
        RecorderDecision decision = RecorderAddressPolicy.check(
                "169.254.169.254", List.of(ip("169.254.169.254")), List.of());

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.code()).isEqualTo("recorder.address.metadata");
    }

    @Test
    @DisplayName("metadata host aliases are blocked before DNS matters")
    void metadataHostAliasBlocked() throws Exception {
        RecorderDecision decision = RecorderAddressPolicy.check(
                "metadata.google.internal", List.of(ip("10.10.20.30")), List.of());

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.code()).isEqualTo("recorder.address.metadata");
    }

    @Test
    @DisplayName("link-local ranges are blocked")
    void linkLocalBlocked() throws Exception {
        RecorderDecision decision = RecorderAddressPolicy.check(
                "link-local.internal", List.of(ip("169.254.1.10")), List.of());

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.code()).isEqualTo("recorder.address.linkLocal");
    }

    @Test
    @DisplayName("deployment-prohibited ranges such as Kubernetes service CIDRs are blocked")
    void prohibitedRangeBlocked() throws Exception {
        RecorderDecision decision = RecorderAddressPolicy.check(
                "kubernetes.default.svc", List.of(ip("10.96.0.1")),
                List.of(CidrBlock.parse("10.96.0.0/12")));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.code()).isEqualTo("recorder.address.prohibitedRange");
    }

    @Test
    @DisplayName("destinations must be resolved and frozen before session start")
    void unresolvedBlocked() {
        RecorderDecision decision = RecorderAddressPolicy.check(
                "proposal.perf.internal", List.of(), List.of());

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.code()).isEqualTo("recorder.address.unresolved");
    }

    private static InetAddress ip(String value) throws Exception {
        return InetAddress.getByName(value);
    }
}
