package com.perfplatform.domain.spec;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A typed objective threshold parsed from its authored form: {@code 750ms}, {@code 8s},
 * {@code 1%}.
 *
 * <p>Objectives are the pass/fail contract of a performance test, so a threshold must never be
 * compared as text. Parsing it into a value and a unit at specification time means an unreadable
 * or unit-less threshold is rejected during validation rather than silently misjudged during
 * evaluation — which would report a PASS or a BREACH that the numbers do not support.
 *
 * <p>Durations are normalized to milliseconds so {@code 1s} and {@code 1000ms} are the same
 * threshold and produce the same digest.
 */
public record Threshold(BigDecimal value, Unit unit) {

    /** The dimension a threshold is expressed in. */
    public enum Unit {
        /** A latency or duration, normalized to milliseconds. */
        MILLISECONDS,
        /** A rate expressed as a percentage, such as an error-rate ceiling. */
        PERCENT
    }

    private static final Pattern PATTERN =
            Pattern.compile("^(\\d+(?:\\.\\d+)?)\\s*(ms|s|%)$");

    public Threshold {
        if (value == null) {
            throw new IllegalArgumentException("Threshold value must not be null");
        }
        if (unit == null) {
            throw new IllegalArgumentException("Threshold unit must not be null");
        }
        if (value.signum() < 0) {
            throw new IllegalArgumentException("Threshold must not be negative");
        }
        if (unit == Unit.PERCENT && value.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new IllegalArgumentException("A percentage threshold must not exceed 100");
        }
        value = value.stripTrailingZeros();
    }

    /**
     * Parses a threshold from its authored form.
     *
     * @throws IllegalArgumentException if the value is null, blank, or carries no recognized unit
     */
    public static Threshold parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Threshold must not be null or blank");
        }
        String text = raw.trim().toLowerCase(Locale.ROOT);

        Matcher matcher = PATTERN.matcher(text);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                    "Threshold '" + raw + "' must be a number followed by ms, s or % "
                            + "(for example 750ms, 8s or 1%)");
        }

        BigDecimal amount = new BigDecimal(matcher.group(1));
        return switch (matcher.group(2)) {
            case "ms" -> new Threshold(amount, Unit.MILLISECONDS);
            case "s" -> new Threshold(amount.multiply(BigDecimal.valueOf(1000)), Unit.MILLISECONDS);
            case "%" -> new Threshold(amount, Unit.PERCENT);
            default -> throw new IllegalStateException("Unreachable unit: " + matcher.group(2));
        };
    }

    /** Canonical text form. Milliseconds and percent render exactly, so digests are stable. */
    public String canonicalForm() {
        BigDecimal normalized = value.stripTrailingZeros();
        if (normalized.scale() < 0) {
            normalized = normalized.setScale(0, RoundingMode.UNNECESSARY);
        }
        return normalized.toPlainString() + (unit == Unit.PERCENT ? "%" : "ms");
    }

    @Override
    public String toString() {
        return canonicalForm();
    }
}
