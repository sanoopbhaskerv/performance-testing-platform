package com.perfplatform.domain.recorder;

import com.perfplatform.domain.target.TargetAddress;

import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/**
 * A normalized browser origin.
 *
 * <p>The route guard compares origins, not raw URLs. Paths, queries and fragments can vary during
 * a recording; scheme, host and port are the boundary that decides whether the browser stayed on a
 * registered target or moved somewhere else.
 */
public record RecorderOrigin(String scheme, String host, int port) {

    public RecorderOrigin {
        scheme = requireScheme(scheme);
        host = requireHost(host);
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("Port " + port + " is outside 1-65535");
        }
    }

    public static RecorderOrigin fromTarget(TargetAddress target) {
        return new RecorderOrigin(target.scheme(), target.host(), target.port());
    }

    /** Parses an origin or URL and discards path/query/fragment. */
    public static RecorderOrigin parse(String value) {
        URI uri = parseUri(value);
        if (uri.getHost() == null) {
            throw new IllegalArgumentException("Recorder origin must include a host");
        }
        String scheme = requireScheme(uri.getScheme());
        int port = uri.getPort() != -1 ? uri.getPort() : defaultPort(scheme);
        return new RecorderOrigin(scheme, uri.getHost(), port);
    }

    static RecorderOrigin fromUri(URI uri, RecorderRequestKind kind) {
        Objects.requireNonNull(uri, "uri");
        if (uri.getHost() == null) {
            throw new IllegalArgumentException("Destination must include a host");
        }
        String scheme = normalizedScheme(uri.getScheme(), kind);
        int port = uri.getPort() != -1 ? uri.getPort() : defaultPort(scheme);
        return new RecorderOrigin(scheme, uri.getHost(), port);
    }

    static URI parseUri(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("URL must not be blank");
        }
        try {
            URI uri = URI.create(value.trim());
            if (!uri.isAbsolute()) {
                throw new IllegalArgumentException("URL must be absolute");
            }
            return uri;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("URL '" + value + "' is not valid");
        }
    }

    /** Raw IP origins are allowed only when the registered target itself is that exact IP origin. */
    public boolean isIpLiteral() {
        String candidate = host;
        if (candidate.startsWith("[") && candidate.endsWith("]")) {
            candidate = candidate.substring(1, candidate.length() - 1);
        }
        if (!candidate.contains(":") && !candidate.matches("^[0-9.]+$")) {
            return false;
        }
        try {
            InetAddress parsed = InetAddress.getByName(candidate);
            return parsed.getHostAddress().equalsIgnoreCase(host)
                    || candidate.contains(":");
        } catch (java.net.UnknownHostException e) {
            return false;
        }
    }

    public String value() {
        boolean defaultPort = ("https".equals(scheme) && port == 443)
                || ("http".equals(scheme) && port == 80);
        return defaultPort ? scheme + "://" + host : scheme + "://" + host + ":" + port;
    }

    private static String requireScheme(String value) {
        String scheme = value == null ? "" : value.toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("Recorder origin scheme must be http or https");
        }
        return scheme;
    }

    private static String requireHost(String value) {
        String host = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (host.isBlank()) {
            throw new IllegalArgumentException("Recorder origin host must not be blank");
        }
        return host;
    }

    private static String normalizedScheme(String scheme, RecorderRequestKind kind) {
        if (scheme == null) {
            throw new IllegalArgumentException("Destination must include a scheme");
        }
        String lower = scheme.toLowerCase(Locale.ROOT);
        if ("ws".equals(lower) && kind == RecorderRequestKind.WEBSOCKET) {
            return "http";
        }
        if ("wss".equals(lower) && kind == RecorderRequestKind.WEBSOCKET) {
            return "https";
        }
        return requireScheme(lower);
    }

    private static int defaultPort(String scheme) {
        return "https".equals(scheme) ? 443 : 80;
    }
}
