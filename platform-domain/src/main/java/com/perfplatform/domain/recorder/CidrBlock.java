package com.perfplatform.domain.recorder;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Objects;

/**
 * A network range used by recorder address policy.
 *
 * <p>Kept in the domain rather than delegated to Kubernetes because the browser route guard and the
 * network layer must agree before a recorder pod exists. Kubernetes {@code NetworkPolicy} can only
 * enforce CIDRs, so hostname decisions are resolved and frozen into these ranges or concrete IPs by
 * the control plane.
 */
public record CidrBlock(InetAddress network, int prefixLength) {

    public CidrBlock {
        Objects.requireNonNull(network, "network");
        int bits = network.getAddress().length * 8;
        if (prefixLength < 0 || prefixLength > bits) {
            throw new IllegalArgumentException(
                    "Prefix length " + prefixLength + " is outside 0-" + bits);
        }
    }

    /** Parses {@code 10.96.0.0/12}, {@code 169.254.169.254/32}, or an exact address. */
    public static CidrBlock parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("CIDR block must not be blank");
        }
        String trimmed = value.trim();
        int slash = trimmed.indexOf('/');
        String address = slash < 0 ? trimmed : trimmed.substring(0, slash);

        InetAddress parsed;
        try {
            parsed = InetAddress.getByName(address);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("CIDR address '" + address + "' is not valid");
        }

        int bits = parsed.getAddress().length * 8;
        int prefix = slash < 0 ? bits : Integer.parseInt(trimmed.substring(slash + 1));
        return new CidrBlock(parsed, prefix);
    }

    /** Whether this range contains the address. IPv4 and IPv6 ranges never match each other. */
    public boolean contains(InetAddress address) {
        Objects.requireNonNull(address, "address");
        byte[] left = network.getAddress();
        byte[] right = address.getAddress();
        if (left.length != right.length) {
            return false;
        }

        int fullBytes = prefixLength / 8;
        if (fullBytes > 0 && !Arrays.equals(
                Arrays.copyOf(left, fullBytes), Arrays.copyOf(right, fullBytes))) {
            return false;
        }

        int remainingBits = prefixLength % 8;
        if (remainingBits == 0) {
            return true;
        }
        int mask = 0xFF << (8 - remainingBits);
        return (left[fullBytes] & mask) == (right[fullBytes] & mask);
    }

    @Override
    public String toString() {
        return network.getHostAddress() + "/" + prefixLength;
    }
}
