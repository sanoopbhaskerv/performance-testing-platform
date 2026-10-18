package com.perfplatform.domain.target;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Decides whether the platform is permitted to point load at an address.
 *
 * <p>This is the control that stops a self-service load-testing platform being usable as an attack
 * tool. It is enforced server-side, at the moment a workload is about to be rendered, and it applies
 * to the <em>resolved</em> address rather than to the name — a hostname that resolves to a
 * link-local address is exactly the case a name-based check misses.
 *
 * <p>Deny by default in the ranges that matter. CLAUDE.md §20 requires blocking metadata and
 * link-local ranges: {@code 169.254.169.254} is the cloud instance-metadata endpoint, and a load
 * test aimed at it is either a mistake or an attempt to harvest credentials. Loopback is permitted
 * because local development targets it deliberately.
 *
 * <p>A hostname allowlist is the other half of this control and is deliberately <b>not</b> here: it
 * is per-deployment configuration, not a universal rule, and CLAUDE.md §31 makes enabling
 * unrestricted or production targets a decision to pause on. What this class provides is the floor
 * that holds regardless of how permissive a deployment's allowlist is.
 */
public final class TargetPolicy {

    /** Addresses that are never a legitimate load-test target, whatever the configuration says. */
    private static final Set<String> BLOCKED_EXACT = Set.of(
            // Cloud instance metadata: AWS, GCP, Azure, OpenStack all serve credentials here.
            "169.254.169.254",
            "fd00:ec2::254",
            // GCP and Alibaba metadata aliases.
            "metadata.google.internal",
            "100.100.100.200");

    private TargetPolicy() {
    }

    /** Why an address was refused. */
    public record Refusal(String code, String reason) {
    }

    /**
     * Checks an address.
     *
     * @return empty when the address may be targeted, or the reason it may not
     */
    public static java.util.Optional<Refusal> check(TargetAddress target) {
        String host = target.host().toLowerCase(Locale.ROOT);

        if (BLOCKED_EXACT.contains(host)) {
            return java.util.Optional.of(new Refusal("target.metadataEndpoint",
                    "This address is a cloud instance-metadata endpoint and is never a permitted "
                            + "load-test target."));
        }

        List<InetAddress> resolved;
        try {
            resolved = List.of(InetAddress.getAllByName(target.host()));
        } catch (UnknownHostException e) {
            // Unresolvable is refused rather than allowed. A target nothing can find will produce a
            // run of connection errors, and reporting that as a performance result is misleading.
            return java.util.Optional.of(new Refusal("target.unresolvable",
                    "'" + target.host() + "' could not be resolved from this runner cell."));
        }

        for (InetAddress address : resolved) {
            if (address.isLinkLocalAddress()) {
                return java.util.Optional.of(new Refusal("target.linkLocal",
                        "'" + target.host() + "' resolves to a link-local address, which is not a "
                                + "permitted load-test target."));
            }
            if (address.isMulticastAddress()) {
                return java.util.Optional.of(new Refusal("target.multicast",
                        "'" + target.host() + "' resolves to a multicast address. Load cannot be "
                                + "directed at a multicast group."));
            }
            if (address.isAnyLocalAddress()) {
                return java.util.Optional.of(new Refusal("target.wildcard",
                        "'" + target.host() + "' resolves to a wildcard address."));
            }
            if (BLOCKED_EXACT.contains(address.getHostAddress())) {
                return java.util.Optional.of(new Refusal("target.metadataEndpoint",
                        "'" + target.host() + "' resolves to a cloud instance-metadata endpoint."));
            }
        }

        return java.util.Optional.empty();
    }

    /**
     * @throws TargetNotPermittedException if the address may not be targeted
     */
    public static void require(TargetAddress target) {
        check(target).ifPresent(refusal -> {
            throw new TargetNotPermittedException(refusal);
        });
    }

    /** Raised when a target is refused by policy. */
    public static class TargetNotPermittedException extends RuntimeException {
        private final transient Refusal refusal;

        public TargetNotPermittedException(Refusal refusal) {
            super(refusal.reason());
            this.refusal = refusal;
        }

        public Refusal refusal() {
            return refusal;
        }
    }
}
