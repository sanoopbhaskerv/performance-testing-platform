package com.perfplatform.domain.spec.support;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Parses enum values as they are authored in a specification.
 *
 * <p>Specifications are written by hand in YAML, where {@code non-production} reads better than
 * {@code NON_PRODUCTION}. This accepts either, along with any case, and rejects everything else
 * with a message listing the permitted values — so a consumer who mistypes an enum learns the
 * valid set immediately instead of receiving a bare parse failure.
 *
 * <p>Deliberately narrow: it exists only to give every specification enum the same authoring
 * ergonomics and the same error message, and is not a home for unrelated helpers.
 */
public final class SpecEnums {

    private SpecEnums() {
    }

    /**
     * @param type      the enum to parse into
     * @param raw       the authored value, e.g. {@code non-production}
     * @param fieldName used in the error message, e.g. {@code target class}
     * @throws IllegalArgumentException if the value is null, blank or not a member of the enum
     */
    public static <E extends Enum<E>> E parse(Class<E> type, String raw, String fieldName) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be null or blank");
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (E candidate : type.getEnumConstants()) {
            if (candidate.name().equals(normalized)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException(
                "Unsupported " + fieldName + " '" + raw + "'. Supported values: " + permitted(type));
    }

    /** The permitted values in authored form, lowercase and hyphenated. */
    public static <E extends Enum<E>> String permitted(Class<E> type) {
        return Arrays.stream(type.getEnumConstants())
                .map(value -> value.name().toLowerCase(Locale.ROOT).replace('_', '-'))
                .collect(Collectors.joining(", "));
    }
}
