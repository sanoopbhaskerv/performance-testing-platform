package com.perfplatform.domain.target;

import java.net.URI;
import java.util.Objects;

/**
 * Where a rendered workload sends traffic.
 *
 * <p>Resolved server-side and handed to the engine. It is deliberately <b>not</b> part of the
 * specification: a document that could name its own target would route around the allowlist it was
 * validated against, and CLAUDE.md §20 requires target safety to be enforced by the platform rather
 * than trusted from generated JMX or imported code.
 *
 * <p>Broken into parts rather than passed as a URL because JMeter addresses host, protocol and port
 * as separate fields. Splitting it here, once, is better than every engine re-parsing a string and
 * disagreeing about defaults.
 *
 * @param scheme {@code http} or {@code https}
 * @param host   hostname or address
 * @param port   explicit port; defaulted from the scheme when the URL omits it
 */
public record TargetAddress(String scheme, String host, int port) {

    public TargetAddress {
        Objects.requireNonNull(scheme, "scheme");
        Objects.requireNonNull(host, "host");
        if (host.isBlank()) {
            throw new IllegalArgumentException("Target host must not be blank");
        }
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException(
                    "Target scheme must be http or https, not '" + scheme + "'");
        }
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("Target port " + port + " is outside 1–65535");
        }
    }

    /**
     * Parses a base URL into its parts.
     *
     * @throws IllegalArgumentException if the URL is unusable as a load-test target
     */
    public static TargetAddress parse(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("Target base URL must not be null or blank");
        }

        URI uri;
        try {
            uri = URI.create(baseUrl.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Target '" + baseUrl + "' is not a valid URL");
        }

        String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        if (scheme == null || uri.getHost() == null) {
            throw new IllegalArgumentException(
                    "Target '" + baseUrl + "' must be an absolute URL including a scheme and host");
        }

        int port = uri.getPort() != -1 ? uri.getPort() : ("https".equals(scheme) ? 443 : 80);
        return new TargetAddress(scheme, uri.getHost(), port);
    }

    /** The address as a URL, for logging and reports. */
    public String baseUrl() {
        boolean defaultPort = ("https".equals(scheme) && port == 443)
                || ("http".equals(scheme) && port == 80);
        return defaultPort ? scheme + "://" + host : scheme + "://" + host + ":" + port;
    }
}
