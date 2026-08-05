package com.perfplatform.domain.recorder;

import java.net.InetAddress;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Address floor for the interactive recorder.
 *
 * <p>This is stricter than {@code TargetPolicy}: ordinary local load tests may target loopback for
 * development, but an interactive browser inside a runner network must not use loopback, link-local
 * or metadata addresses as a general browsing tunnel.
 */
public final class RecorderAddressPolicy {

    private static final Set<String> BLOCKED_HOSTS = Set.of(
            "metadata.google.internal");

    private static final Set<String> BLOCKED_EXACT_ADDRESSES = Set.of(
            "169.254.169.254",
            "fd00:ec2::254",
            "100.100.100.200");

    private RecorderAddressPolicy() {
    }

    /** Checks against the strict floor, with no allowances. */
    public static RecorderDecision check(String host, List<InetAddress> resolvedAddresses,
            List<CidrBlock> prohibitedRanges) {
        return check(host, resolvedAddresses, prohibitedRanges, RecorderAddressAllowances.strict());
    }

    public static RecorderDecision check(String host, List<InetAddress> resolvedAddresses,
            List<CidrBlock> prohibitedRanges, RecorderAddressAllowances allowances) {

        RecorderAddressAllowances effective =
                allowances == null ? RecorderAddressAllowances.strict() : allowances;
        String normalizedHost = host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
        if (normalizedHost.isBlank()) {
            return RecorderDecision.block("recorder.address.hostMissing",
                    "Destination host is missing.");
        }
        if (BLOCKED_HOSTS.contains(normalizedHost)) {
            return RecorderDecision.block("recorder.address.metadata",
                    "Metadata hostnames are not permitted from a recording browser.");
        }

        List<InetAddress> addresses = resolvedAddresses == null ? List.of() : List.copyOf(resolvedAddresses);
        if (addresses.isEmpty()) {
            return RecorderDecision.block("recorder.address.unresolved",
                    "Recorder destinations must be resolved and frozen before the session starts.");
        }

        List<CidrBlock> ranges = prohibitedRanges == null ? List.of() : List.copyOf(prohibitedRanges);
        for (InetAddress address : addresses) {
            RecorderDecision decision = checkOne(normalizedHost, address, ranges, effective);
            if (!decision.allowed()) {
                return decision;
            }
        }
        return RecorderDecision.allow();
    }

    private static RecorderDecision checkOne(String host, InetAddress address,
            List<CidrBlock> prohibitedRanges, RecorderAddressAllowances allowances) {

        Objects.requireNonNull(address, "address");
        String literal = address.getHostAddress().toLowerCase(Locale.ROOT);
        if (BLOCKED_EXACT_ADDRESSES.contains(host) || BLOCKED_EXACT_ADDRESSES.contains(literal)) {
            return RecorderDecision.block("recorder.address.metadata",
                    "Cloud metadata endpoints are not permitted from a recording browser.");
        }
        if (address.isLoopbackAddress() && !allowances.loopbackPermitted()) {
            return RecorderDecision.block("recorder.address.loopback",
                    "Loopback addresses are not permitted from a recording browser.");
        }
        if (address.isLinkLocalAddress()) {
            return RecorderDecision.block("recorder.address.linkLocal",
                    "Link-local addresses are not permitted from a recording browser.");
        }
        if (address.isAnyLocalAddress()) {
            return RecorderDecision.block("recorder.address.wildcard",
                    "Wildcard addresses are not permitted from a recording browser.");
        }
        if (address.isMulticastAddress()) {
            return RecorderDecision.block("recorder.address.multicast",
                    "Multicast addresses are not permitted from a recording browser.");
        }
        for (CidrBlock range : prohibitedRanges) {
            if (range.contains(address)) {
                return RecorderDecision.block("recorder.address.prohibitedRange",
                        "Destination resolves inside prohibited range " + range + ".");
            }
        }
        return RecorderDecision.allow();
    }
}
