package com.perfplatform.api.v2.spec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads the published example specifications from {@code specification/examples/}.
 *
 * <p>Tests run against the shipped files rather than against inline copies, so an example that
 * stops validating fails the build. Examples are documentation consumers copy from; a stale one
 * teaches the wrong thing quietly.
 */
final class ExampleFixtures {

    private ExampleFixtures() {
    }

    static String valid(String name) {
        return read(examplesRoot().resolve("valid").resolve(name));
    }

    static String invalid(String name) {
        return read(examplesRoot().resolve("invalid").resolve(name));
    }

    private static Path examplesRoot() {
        Path candidate = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (candidate != null) {
            Path examples = candidate.resolve("specification/examples");
            if (Files.isDirectory(examples)) {
                return examples;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException(
                "Could not locate specification/examples from " + System.getProperty("user.dir"));
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Missing example fixture: " + path, e);
        }
    }
}
