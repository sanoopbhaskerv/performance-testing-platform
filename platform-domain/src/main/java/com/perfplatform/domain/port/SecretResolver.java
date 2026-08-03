package com.perfplatform.domain.port;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Resolves secret references into short-lived material for infrastructure adapters.
 *
 * <p>Specifications and deployment configuration carry references such as
 * {@code k8s://session-broker/client-token}; they never carry the value. Implementations must not
 * log resolved material, persist it as an artifact, or expose it through API responses.
 */
public interface SecretResolver {

    /**
     * Resolved secret material.
     *
     * @param reference the reference that was resolved
     * @param value resolved secret value; credential material
     * @param expiresAt optional expiry reported by the backing provider
     */
    record SecretMaterial(String reference, String value, Optional<Instant> expiresAt) {

        public SecretMaterial {
            if (reference == null || reference.isBlank()) {
                throw new IllegalArgumentException("reference must not be blank");
            }
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("secret value must not be blank");
            }
            reference = reference.trim();
            expiresAt = expiresAt == null ? Optional.empty() : expiresAt;
        }

        public SecretMaterial(String reference, String value) {
            this(reference, value, Optional.empty());
        }

        @Override
        public String toString() {
            return "SecretMaterial[reference=%s, value=<redacted>, expiresAt=%s]"
                    .formatted(reference, expiresAt.map(Instant::toString).orElse("<unspecified>"));
        }
    }

    /** Raised when a reference cannot be resolved. Messages must not include secret values. */
    class SecretResolutionException extends RuntimeException {
        public SecretResolutionException(String reference, String detail) {
            super("Secret reference '" + Objects.requireNonNull(reference, "reference")
                    + "' could not be resolved: " + Objects.requireNonNull(detail, "detail"));
        }

        public SecretResolutionException(String reference, String detail, Throwable cause) {
            super("Secret reference '" + Objects.requireNonNull(reference, "reference")
                    + "' could not be resolved: " + Objects.requireNonNull(detail, "detail"),
                    cause);
        }
    }

    SecretMaterial resolve(String reference);
}
