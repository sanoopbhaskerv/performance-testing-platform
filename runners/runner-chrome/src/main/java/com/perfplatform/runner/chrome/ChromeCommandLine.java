package com.perfplatform.runner.chrome;

import com.perfplatform.domain.recorder.RecorderSessionPolicy;
import com.perfplatform.domain.recorder.RecorderViewport;

import java.net.InetAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The exact arguments the recording browser is started with.
 *
 * <p>Separated from the launching so the command line is a value that can be asserted on. The
 * security-relevant part of starting Chrome is not the starting — it is which flags are present,
 * and a test that reads the list is worth more than one that reads a log line afterwards.
 *
 * <p>The important flag is {@code --host-resolver-rules}. It pins every allowed hostname to the
 * address frozen when the session started and maps everything else to {@code ~NOTFOUND}, so a name
 * outside the allowlist does not resolve inside this browser at all. That is the DNS-rebinding
 * control from the recorder security review §4.2, applied one layer below the route guard: even if
 * an interception were missed, the name has nowhere to go.
 */
final class ChromeCommandLine {

    private ChromeCommandLine() {
    }

    /**
     * @param debugPort 0 lets Chrome choose; the port is then read from {@code DevToolsActivePort}
     */
    static List<String> build(Path binary, Path userDataDir, RecorderSessionPolicy policy,
            RecorderViewport viewport, boolean headless, int debugPort) {

        List<String> command = new ArrayList<>();
        command.add(binary.toString());

        if (headless) {
            command.add("--headless=new");
        }
        command.add("--remote-debugging-port=" + debugPort);
        command.add("--user-data-dir=" + userDataDir);
        command.add("--window-size=" + viewport.width() + "," + viewport.height());

        // Nothing may resolve except this session's frozen destinations.
        command.add("--host-resolver-rules=" + hostResolverRules(policy));
        // A system proxy would resolve names on the browser's behalf and defeat the rule above.
        command.add("--no-proxy-server");

        // Chrome's own network chatter is not part of the recording and is not in the allowlist,
        // so leaving it on produces a stream of blocked-destination noise for the user to read.
        command.add("--no-first-run");
        command.add("--no-default-browser-check");
        command.add("--disable-background-networking");
        command.add("--disable-component-update");
        command.add("--disable-client-side-phishing-detection");
        command.add("--disable-default-apps");
        command.add("--disable-sync");
        command.add("--disable-breakpad");
        command.add("--disable-domain-reliability");
        command.add("--metrics-recording-only");
        command.add("--no-service-autorun");
        command.add("--disable-features=Translate,MediaRouter,OptimizationHints,InterestFeedContentSuggestions");

        // Extensions and the developer's own browsing profile are the two ways a recording session
        // could pick up state that does not belong to it. The throwaway --user-data-dir handles the
        // second; --incognito on top of it buys nothing and complicates target attachment.
        command.add("--disable-extensions");

        // The first navigation goes through Page.navigate so the route guard sees it. Starting on
        // a real URL would put one navigation outside the guard's view.
        command.add("about:blank");
        return List.copyOf(command);
    }

    /**
     * Builds Chrome's resolver rules: every allowed host pinned, everything else unresolvable.
     *
     * <p>Rules are evaluated in order, so the specific rules must precede the catch-all. IP-literal
     * origins are skipped — there is no name to pin, and the address is already the thing the
     * allowlist named.
     *
     * <h2>One address, and what that costs</h2>
     *
     * <p>{@code MAP} takes a single replacement, so a host that resolved to several addresses can
     * only be pinned to one. That is a real limitation: a service listening on only <em>another</em>
     * of its addresses is unreachable from the recording browser even though it is up. Loopback is
     * where this bites in practice — a dev server on {@code [::1]} with the browser pinned to
     * {@code 127.0.0.1} refuses the connection — so a loopback host under the relaxed floor is
     * {@code EXCLUDE}d instead, letting Chrome resolve it normally and try both families.
     *
     * <p>That is not a hole. {@code EXCLUDE} is emitted only when the session's allowances already
     * permit loopback and every address the host resolved to is loopback, which the address floor
     * has already accepted. Rebinding a name to loopback is precisely what this mode permits.
     */
    static String hostResolverRules(RecorderSessionPolicy policy) {
        boolean loopbackPermitted = policy.allowances().loopbackPermitted();

        String rules = policy.allowedOrigins().values().stream()
                .filter(allowed -> !allowed.origin().isIpLiteral())
                .filter(allowed -> !allowed.resolvedAddresses().isEmpty())
                .sorted(Comparator.comparing(allowed -> allowed.origin().value()))
                .map(allowed -> {
                    String host = allowed.origin().host();
                    boolean allLoopback = allowed.resolvedAddresses().stream()
                            .allMatch(InetAddress::isLoopbackAddress);
                    return loopbackPermitted && allLoopback
                            ? "EXCLUDE " + host
                            : "MAP " + host + " "
                                    + allowed.resolvedAddresses().get(0).getHostAddress();
                })
                .distinct()
                .collect(Collectors.joining(","));

        return rules.isEmpty() ? "MAP * ~NOTFOUND" : rules + ",MAP * ~NOTFOUND";
    }
}
