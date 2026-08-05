package com.perfplatform.runner.chrome;

import com.perfplatform.domain.recorder.RecorderAddressAllowances;
import com.perfplatform.domain.recorder.RecorderOrigin;
import com.perfplatform.domain.recorder.RecorderSessionPolicy;
import com.perfplatform.domain.recorder.RecorderViewport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChromeCommandLineTest {

    @Test
    @DisplayName("every allowed host is pinned to its frozen address and everything else is unresolvable")
    void dnsIsFrozen() {
        String rules = ChromeCommandLine.hostResolverRules(policyWithAssets());

        assertThat(rules).isEqualTo(
                "MAP proposal.perf.internal 10.10.20.30,"
                        + "MAP static.perf.internal 10.10.20.31,"
                        + "MAP * ~NOTFOUND");
    }

    @Test
    @DisplayName("the catch-all is last, so a specific pin is never shadowed by it")
    void catchAllIsLast() {
        String rules = ChromeCommandLine.hostResolverRules(policyWithAssets());

        assertThat(rules).endsWith("MAP * ~NOTFOUND");
        assertThat(rules.indexOf("MAP *")).isGreaterThan(rules.indexOf("MAP proposal"));
    }

    @Test
    @DisplayName("an IP-literal target needs no name pinned, but nothing else may still resolve")
    void ipLiteralTargetHasNoMapping() {
        RecorderSessionPolicy policy = RecorderSessionPolicy
                .forTarget(RecorderOrigin.parse("https://10.10.20.30:8443"), List.of(ip("10.10.20.30")))
                .build();

        assertThat(ChromeCommandLine.hostResolverRules(policy)).isEqualTo("MAP * ~NOTFOUND");
    }

    /**
     * A dev server that binds only {@code [::1]} refused every connection when the browser was
     * pinned to {@code 127.0.0.1}. MAP takes one replacement, so a multi-address loopback host is
     * excluded from remapping instead and Chrome tries both families.
     */
    @Test
    @DisplayName("a loopback host under the relaxed floor is excluded, not pinned to one family")
    void loopbackIsExcludedRatherThanPinned() {
        RecorderSessionPolicy policy = RecorderSessionPolicy
                .forTarget(RecorderOrigin.parse("http://localhost:5174"),
                        List.of(ip("127.0.0.1"), ip("::1")))
                .withAllowances(RecorderAddressAllowances.permittingLoopback())
                .build();

        assertThat(ChromeCommandLine.hostResolverRules(policy))
                .isEqualTo("EXCLUDE localhost,MAP * ~NOTFOUND");
    }

    @Test
    @DisplayName("everything else is still unresolvable, exclusion or not")
    void exclusionDoesNotOpenTheCatchAll() {
        RecorderSessionPolicy policy = RecorderSessionPolicy
                .forTarget(RecorderOrigin.parse("http://localhost:5174"), List.of(ip("127.0.0.1")))
                .withAllowances(RecorderAddressAllowances.permittingLoopback())
                .build();

        assertThat(ChromeCommandLine.hostResolverRules(policy)).endsWith("MAP * ~NOTFOUND");
    }

    @Test
    @DisplayName("a non-loopback host is still pinned, even under the relaxed floor")
    void relaxedFloorDoesNotStopPinningRealHosts() {
        RecorderSessionPolicy policy = RecorderSessionPolicy
                .forTarget(RecorderOrigin.parse("https://proposal.perf.internal"),
                        List.of(ip("10.10.20.30")))
                .withAllowances(RecorderAddressAllowances.permittingLoopback())
                .build();

        assertThat(ChromeCommandLine.hostResolverRules(policy))
                .isEqualTo("MAP proposal.perf.internal 10.10.20.30,MAP * ~NOTFOUND");
    }

    @Test
    @DisplayName("the browser is started from an argument list, never a shell string")
    void argumentsAreAList() {
        List<String> command = command();

        assertThat(command).isNotEmpty();
        assertThat(command).noneMatch(argument ->
                argument.contains("&&") || argument.contains("|") || argument.contains(";"));
        assertThat(command.get(0)).isEqualTo("/opt/chrome");
    }

    @Test
    @DisplayName("the command carries the frozen resolver rules and disables the proxy that would bypass them")
    void resolverRulesAreOnTheCommandLine() {
        List<String> command = command();

        assertThat(command).contains("--no-proxy-server");
        assertThat(command).anyMatch(argument ->
                argument.startsWith("--host-resolver-rules=") && argument.endsWith("~NOTFOUND"));
    }

    @Test
    @DisplayName("the browser starts on about:blank so the first real navigation passes the route guard")
    void startsBlank() {
        assertThat(command()).last().isEqualTo("about:blank");
        // The frozen resolver rules name the host, which is fine. What must not appear is a URL:
        // Chrome would load it before Fetch interception is enabled, outside the route guard.
        assertThat(command()).noneMatch(argument -> argument.startsWith("http"));
    }

    @Test
    @DisplayName("the throwaway profile and viewport are pinned to this session")
    void profileAndViewportArePerSession() {
        List<String> command = command();

        assertThat(command).contains("--user-data-dir=/tmp/session-a", "--window-size=1280,800");
    }

    @Test
    @DisplayName("chrome's own background networking is off, so it does not pollute blocked destinations")
    void backgroundNetworkingIsOff() {
        assertThat(command()).contains(
                "--disable-background-networking",
                "--disable-component-update",
                "--disable-sync",
                "--disable-extensions");
    }

    @Test
    @DisplayName("headless is a setting, not a fixture")
    void headlessIsOptional() {
        assertThat(command()).contains("--headless=new");
        assertThat(ChromeCommandLine.build(Path.of("/opt/chrome"), Path.of("/tmp/session-a"),
                policyWithAssets(), RecorderViewport.DEFAULT, false, 0))
                .doesNotContain("--headless=new");
    }

    private static List<String> command() {
        return ChromeCommandLine.build(Path.of("/opt/chrome"), Path.of("/tmp/session-a"),
                policyWithAssets(), RecorderViewport.DEFAULT, true, 0);
    }

    private static RecorderSessionPolicy policyWithAssets() {
        return RecorderSessionPolicy
                .forTarget(RecorderOrigin.parse("https://proposal.perf.internal"),
                        List.of(ip("10.10.20.30")))
                .allowAssetOrigin("https://static.perf.internal", List.of(ip("10.10.20.31")))
                .withAllowances(RecorderAddressAllowances.strict())
                .build();
    }

    private static InetAddress ip(String literal) {
        try {
            return InetAddress.getByName(literal);
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }
}
