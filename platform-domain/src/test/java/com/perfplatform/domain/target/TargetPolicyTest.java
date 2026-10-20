package com.perfplatform.domain.target;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The control that stops a load-testing platform being usable as an attack tool.
 *
 * <p>These are the rules that hold regardless of how permissive a deployment's allowlist is. The
 * allowlist itself is per-deployment configuration; this is the floor beneath it.
 */
class TargetPolicyTest {

    @Nested
    @DisplayName("parsing a target")
    class Parsing {

        @Test
        @DisplayName("a URL is split into scheme, host and port")
        void parsesAUrl() {
            TargetAddress target = TargetAddress.parse("https://proposal.example.internal:8443");

            assertThat(target.scheme()).isEqualTo("https");
            assertThat(target.host()).isEqualTo("proposal.example.internal");
            assertThat(target.port()).isEqualTo(8443);
        }

        @Test
        @DisplayName("the port defaults from the scheme")
        void portDefaultsFromScheme() {
            assertThat(TargetAddress.parse("https://x.internal").port()).isEqualTo(443);
            assertThat(TargetAddress.parse("http://x.internal").port()).isEqualTo(80);
        }

        @Test
        @DisplayName("a default port is omitted when the address is rendered back")
        void defaultPortIsOmitted() {
            assertThat(TargetAddress.parse("https://x.internal").baseUrl())
                    .isEqualTo("https://x.internal");
            assertThat(TargetAddress.parse("https://x.internal:8443").baseUrl())
                    .isEqualTo("https://x.internal:8443");
        }

        @ParameterizedTest
        @ValueSource(strings = {"proposal.example.internal", "/just/a/path", "ftp://x.internal",
                "file:///etc/passwd", ""})
        @DisplayName("anything that is not an absolute http or https URL is refused")
        void refusesNonHttpUrls(String candidate) {
            assertThatThrownBy(() -> TargetAddress.parse(candidate))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("addresses that are never permitted")
    class Blocked {

        /**
         * {@code 169.254.169.254} serves instance credentials on every major cloud. A load test
         * aimed at it is either a mistake or an attempt to harvest them, and neither should be
         * possible through a self-service platform.
         */
        @Test
        @DisplayName("the cloud instance-metadata endpoint is refused")
        void metadataEndpointIsRefused() {
            TargetAddress metadata = TargetAddress.parse("http://169.254.169.254");

            assertThat(TargetPolicy.check(metadata)).isPresent()
                    .hasValueSatisfying(refusal ->
                            assertThat(refusal.code()).isEqualTo("target.metadataEndpoint"));
        }

        @Test
        @DisplayName("the metadata endpoint's hostname alias is refused too")
        void metadataHostnameIsRefused() {
            assertThat(TargetPolicy.check(TargetAddress.parse("http://metadata.google.internal")))
                    .isPresent();
        }

        /**
         * Checked against the resolved address, not the name. A hostname that resolves to a
         * link-local address is exactly the case a name-based check misses.
         */
        @Test
        @DisplayName("a link-local address is refused")
        void linkLocalIsRefused() {
            assertThat(TargetPolicy.check(TargetAddress.parse("http://169.254.1.1")))
                    .hasValueSatisfying(refusal ->
                            assertThat(refusal.code()).isIn("target.linkLocal",
                                    "target.metadataEndpoint"));
        }

        @Test
        @DisplayName("a wildcard address is refused")
        void wildcardIsRefused() {
            assertThat(TargetPolicy.check(TargetAddress.parse("http://0.0.0.0"))).isPresent();
        }

        /**
         * A target nothing can resolve produces a run of connection errors, and reporting that as a
         * performance result is misleading.
         */
        @Test
        @DisplayName("an unresolvable host is refused rather than attempted")
        void unresolvableIsRefused() {
            TargetAddress missing = TargetAddress.parse(
                    "http://this-host-does-not-exist.invalid");

            assertThat(TargetPolicy.check(missing)).isPresent()
                    .hasValueSatisfying(refusal ->
                            assertThat(refusal.code()).isEqualTo("target.unresolvable"));
        }

        @Test
        @DisplayName("require() raises rather than returning a refusal")
        void requireRaises() {
            assertThatThrownBy(() ->
                    TargetPolicy.require(TargetAddress.parse("http://169.254.169.254")))
                    .isInstanceOf(TargetPolicy.TargetNotPermittedException.class)
                    .hasMessageContaining("metadata");
        }
    }

    /**
     * Loopback is permitted deliberately: local development targets it, and the demo service in
     * this repository runs there.
     */
    @Test
    @DisplayName("loopback is permitted, because local development targets it")
    void loopbackIsPermitted() {
        assertThat(TargetPolicy.check(TargetAddress.parse("http://localhost:8080"))).isEmpty();
        assertThat(TargetPolicy.check(TargetAddress.parse("http://127.0.0.1:8080"))).isEmpty();
    }
}
