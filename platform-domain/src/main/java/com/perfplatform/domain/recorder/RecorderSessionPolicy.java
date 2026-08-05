package com.perfplatform.domain.recorder;

import com.perfplatform.domain.target.RegisteredTarget;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The frozen network policy for one recorder session.
 *
 * <p>A session starts from a registered target and a set of explicitly declared asset/login origins.
 * Each origin carries the addresses resolved before the session starts, which is what lets the
 * route guard and the eventual network policy enforce the same decision. Redirects do not expand
 * this policy; an operator or team administrator must declare the destination ahead of time.
 */
public record RecorderSessionPolicy(
        RecorderOrigin registeredTargetOrigin,
        Map<RecorderOrigin, AllowedOrigin> allowedOrigins,
        List<CidrBlock> prohibitedRanges,
        RecorderAddressAllowances allowances) {

    public RecorderSessionPolicy {
        Objects.requireNonNull(registeredTargetOrigin, "registeredTargetOrigin");
        allowedOrigins = allowedOrigins == null ? Map.of() : Map.copyOf(allowedOrigins);
        prohibitedRanges = prohibitedRanges == null ? List.of() : List.copyOf(prohibitedRanges);
        allowances = allowances == null ? RecorderAddressAllowances.strict() : allowances;
        if (!allowedOrigins.containsKey(registeredTargetOrigin)) {
            throw new IllegalArgumentException("Registered target origin must be allowed");
        }
    }

    public static Builder forTarget(RegisteredTarget target, List<InetAddress> resolvedAddresses) {
        return forTarget(RecorderOrigin.fromTarget(target.address()), resolvedAddresses);
    }

    public static Builder forTarget(RecorderOrigin targetOrigin, List<InetAddress> resolvedAddresses) {
        return new Builder(targetOrigin, resolvedAddresses);
    }

    Optional<AllowedOrigin> find(RecorderOrigin origin) {
        return Optional.ofNullable(allowedOrigins.get(origin));
    }

    boolean isRegisteredTargetOrigin(RecorderOrigin origin) {
        return registeredTargetOrigin.equals(origin);
    }

    /** One origin the browser may contact, with the addresses frozen for this session. */
    public record AllowedOrigin(RecorderOrigin origin, OriginPurpose purpose,
            List<InetAddress> resolvedAddresses) {

        public AllowedOrigin {
            Objects.requireNonNull(origin, "origin");
            Objects.requireNonNull(purpose, "purpose");
            resolvedAddresses = resolvedAddresses == null ? List.of() : List.copyOf(resolvedAddresses);
        }
    }

    public enum OriginPurpose {
        TARGET,
        ASSET,
        LOGIN
    }

    public static final class Builder {
        private final RecorderOrigin registeredTargetOrigin;
        private final Map<RecorderOrigin, AllowedOrigin> allowed = new LinkedHashMap<>();
        private final List<CidrBlock> prohibitedRanges = new ArrayList<>();
        private RecorderAddressAllowances allowances = RecorderAddressAllowances.strict();

        private Builder(RecorderOrigin targetOrigin, List<InetAddress> resolvedAddresses) {
            this.registeredTargetOrigin = Objects.requireNonNull(targetOrigin, "targetOrigin");
            allow(targetOrigin, OriginPurpose.TARGET, resolvedAddresses);
        }

        /**
         * Relaxes the address floor for this session.
         *
         * <p>Only ever reached from local-development configuration; see
         * {@link RecorderAddressAllowances}.
         */
        public Builder withAllowances(RecorderAddressAllowances allowances) {
            this.allowances = allowances == null ? RecorderAddressAllowances.strict() : allowances;
            return this;
        }

        public Builder allowAssetOrigin(String origin, List<InetAddress> resolvedAddresses) {
            return allow(RecorderOrigin.parse(origin), OriginPurpose.ASSET, resolvedAddresses);
        }

        public Builder allowLoginOrigin(String origin, List<InetAddress> resolvedAddresses) {
            return allow(RecorderOrigin.parse(origin), OriginPurpose.LOGIN, resolvedAddresses);
        }

        public Builder prohibit(CidrBlock range) {
            prohibitedRanges.add(Objects.requireNonNull(range, "range"));
            return this;
        }

        public RecorderSessionPolicy build() {
            allowed.values().forEach(entry -> {
                RecorderDecision decision = RecorderAddressPolicy.check(
                        entry.origin().host(), entry.resolvedAddresses(), prohibitedRanges,
                        allowances);
                if (!decision.allowed()) {
                    throw new IllegalArgumentException(decision.reason());
                }
            });
            return new RecorderSessionPolicy(registeredTargetOrigin, allowed, prohibitedRanges,
                    allowances);
        }

        private Builder allow(RecorderOrigin origin, OriginPurpose purpose,
                List<InetAddress> resolvedAddresses) {
            if (origin.isIpLiteral() && purpose != OriginPurpose.TARGET) {
                throw new IllegalArgumentException(
                        "Raw IP origins are permitted only for the registered target.");
            }
            allowed.put(origin, new AllowedOrigin(origin, purpose, resolvedAddresses));
            return this;
        }
    }
}
