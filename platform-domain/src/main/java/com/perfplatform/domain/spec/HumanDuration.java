package com.perfplatform.domain.spec;

import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the compact duration forms consumers write in a specification: {@code 30s}, {@code 5m},
 * {@code 2h}, {@code 90d}.
 *
 * <p>The JDK's {@link Duration#parse} accepts only ISO-8601 ({@code PT5M}), which is correct but
 * hostile to write by hand in YAML. Since the specification is authored by application teams,
 * durations are parsed here and normalized to a {@link Duration} so that everything downstream —
 * validation, capacity planning, plan compilation and governance — works with one type rather
 * than re-parsing strings.
 *
 * <p>ISO-8601 input is also accepted, so a machine-generated specification does not have to be
 * converted before submission.
 */
public final class HumanDuration {

    private static final Pattern COMPACT = Pattern.compile("^(\\d+)\\s*(ms|s|m|h|d)$");

    private HumanDuration() {
    }

    /**
     * Parses a duration.
     *
     * @param raw a compact form such as {@code 5m}, or an ISO-8601 form such as {@code PT5M}
     * @throws IllegalArgumentException if the value is null, blank or not a recognized form
     */
    public static Duration parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Duration must not be null or blank");
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);

        Matcher matcher = COMPACT.matcher(value);
        if (matcher.matches()) {
            long amount = Long.parseLong(matcher.group(1));
            return switch (matcher.group(2)) {
                case "ms" -> Duration.ofMillis(amount);
                case "s" -> Duration.ofSeconds(amount);
                case "m" -> Duration.ofMinutes(amount);
                case "h" -> Duration.ofHours(amount);
                case "d" -> Duration.ofDays(amount);
                default -> throw new IllegalStateException("Unreachable unit: " + matcher.group(2));
            };
        }

        if (value.startsWith("p")) {
            try {
                return Duration.parse(value.toUpperCase(Locale.ROOT));
            } catch (java.time.format.DateTimeParseException e) {
                throw new IllegalArgumentException(
                        "Duration '" + raw + "' is not a valid ISO-8601 duration");
            }
        }

        throw new IllegalArgumentException(
                "Duration '" + raw + "' must be a number followed by ms, s, m, h or d (for example 5m)");
    }

    /**
     * Renders a duration back to the canonical compact form, choosing the largest unit that
     * represents the value exactly. Used by canonicalization so that {@code 60m} and {@code 1h}
     * produce the same digest.
     */
    public static String format(Duration duration) {
        if (duration == null) {
            throw new IllegalArgumentException("Duration must not be null");
        }
        long millis = duration.toMillis();
        if (millis == 0) {
            return "0s";
        }
        if (millis % 86_400_000L == 0) {
            return (millis / 86_400_000L) + "d";
        }
        if (millis % 3_600_000L == 0) {
            return (millis / 3_600_000L) + "h";
        }
        if (millis % 60_000L == 0) {
            return (millis / 60_000L) + "m";
        }
        if (millis % 1_000L == 0) {
            return (millis / 1_000L) + "s";
        }
        return millis + "ms";
    }
}
