package com.perfplatform.domain.execution;

import java.util.Locale;
import java.util.Objects;

/**
 * One runtime credential value for a runner process.
 *
 * <p>Values are intentionally outside the specification, plan, run event and database model. This
 * object exists only in memory between trigger and provisioning, and its string form is redacted so
 * accidental logging does not disclose the value.
 */
public final class RuntimeCredential {

    private final String name;
    private final String environmentVariable;
    private final String value;

    public RuntimeCredential(String name, String value) {
        this.name = requireName(name);
        this.environmentVariable = environmentVariableFor(this.name);
        this.value = Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Runtime credential '" + name + "' must not be blank");
        }
    }

    public String name() {
        return name;
    }

    public String environmentVariable() {
        return environmentVariable;
    }

    public String value() {
        return value;
    }

    public static String environmentVariableFor(String name) {
        return "PERF_AUTH_" + requireName(name)
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]", "_");
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Runtime credential name must not be blank");
        }
        return name.trim();
    }

    @Override
    public String toString() {
        return "RuntimeCredential[name=" + name + ", environmentVariable="
                + environmentVariable + ", value=<redacted>]";
    }
}
