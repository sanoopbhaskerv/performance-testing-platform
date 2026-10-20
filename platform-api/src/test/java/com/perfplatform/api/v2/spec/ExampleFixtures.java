package com.perfplatform.api.v2.spec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads the published specification artifacts from {@code specification/}.
 *
 * <p>Tests run against the shipped files rather than against inline copies, so an example or a
 * schema that stops matching the code fails the build. These are the artifacts consumers copy
 * from and point their editors at; a stale one teaches the wrong thing quietly.
 */
final class ExampleFixtures {

    private ExampleFixtures() {
    }

    static String valid(String name) {
        return read(specificationRoot().resolve("examples/valid").resolve(name));
    }

    static String invalid(String name) {
        return read(specificationRoot().resolve("examples/invalid").resolve(name));
    }

    /** The published JSON Schema that consumers and IDE tooling validate against. */
    static String schema() {
        return read(specificationRoot().resolve("schema/v1alpha1/performance-test.schema.json"));
    }

    private static Path specificationRoot() {
        Path candidate = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (candidate != null) {
            Path specification = candidate.resolve("specification");
            if (Files.isDirectory(specification.resolve("examples"))) {
                return specification;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException(
                "Could not locate specification/ from " + System.getProperty("user.dir"));
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Missing example fixture: " + path, e);
        }
    }
}
