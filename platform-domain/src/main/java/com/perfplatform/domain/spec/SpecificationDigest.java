package com.perfplatform.domain.spec;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The SHA-256 digest of a specification's canonical form.
 *
 * <p>The digest is the platform's identity for "this exact test". It is what makes a run
 * reproducible, and it is checked before a runner's results are accepted: results whose plan
 * digest does not match the run's are rejected rather than merged, because they describe a
 * different test than the one that was approved.
 *
 * <p>Rendered as {@code sha256:<hex>} so the algorithm travels with the value and a future
 * algorithm change is distinguishable rather than silently reinterpreted.
 *
 * @param algorithm always {@code sha256} in this release
 * @param hex       lowercase hexadecimal digest
 */
public record SpecificationDigest(String algorithm, String hex) {

    private static final String ALGORITHM = "sha256";
    private static final String JDK_ALGORITHM = "SHA-256";

    public SpecificationDigest {
        if (algorithm == null || algorithm.isBlank()) {
            throw new IllegalArgumentException("Digest algorithm must not be null or blank");
        }
        if (hex == null || hex.isBlank()) {
            throw new IllegalArgumentException("Digest value must not be null or blank");
        }
    }

    /** Computes the digest of a specification, canonicalizing it first. */
    public static SpecificationDigest of(PerformanceTestSpecification specification) {
        return ofCanonicalForm(SpecificationCanonicalizer.canonicalize(specification));
    }

    /** Computes the digest of an already-canonical form. */
    public static SpecificationDigest ofCanonicalForm(String canonicalForm) {
        if (canonicalForm == null) {
            throw new IllegalArgumentException("Canonical form must not be null");
        }
        try {
            byte[] hash = MessageDigest.getInstance(JDK_ALGORITHM)
                    .digest(canonicalForm.getBytes(StandardCharsets.UTF_8));
            return new SpecificationDigest(ALGORITHM, HexFormat.of().formatHex(hash));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the Java platform; absence means a broken JRE.
            throw new IllegalStateException("SHA-256 is not available in this JVM", e);
        }
    }

    /** Parses a stored {@code sha256:<hex>} value. */
    public static SpecificationDigest parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Digest must not be null or blank");
        }
        int separator = raw.indexOf(':');
        if (separator <= 0 || separator == raw.length() - 1) {
            throw new IllegalArgumentException("Digest must be of the form <algorithm>:<hex>");
        }
        return new SpecificationDigest(raw.substring(0, separator), raw.substring(separator + 1));
    }

    /** The stored and transmitted form, e.g. {@code sha256:9f86d0…}. */
    public String value() {
        return algorithm + ":" + hex;
    }

    /**
     * Constant-time comparison. Digest equality gates whether runner results are accepted, so it
     * is compared without leaking timing information about how much of the value matched.
     */
    public boolean matches(SpecificationDigest other) {
        if (other == null) {
            return false;
        }
        return MessageDigest.isEqual(
                value().getBytes(StandardCharsets.UTF_8),
                other.value().getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String toString() {
        return value();
    }
}
