package com.perfplatform.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Enforces the modular monolith's dependency rules (CLAUDE.md §9).
 *
 * <p>Maven already prevents a circular module dependency — the reactor fails to resolve one — and
 * a module cannot compile against a type it has no dependency on. What Maven does not catch is a
 * module acquiring a forbidden dependency <em>transitively</em>: {@code platform-domain} pulling
 * in Spring through some future addition would compile cleanly while quietly coupling the domain
 * to the framework. This test scans source imports directly, so the rule holds regardless of how
 * a type arrived on the classpath.
 *
 * <p>The rules exist because a domain shaped by its storage schema or by whichever engine was
 * built first cannot express what the platform needs. V1's model became JMeter-specific exactly
 * that way and cannot describe a browser workload.
 *
 * <p>Implemented without a dedicated architecture-testing library so that Increment 1A introduces
 * no new dependency. If the rule set outgrows this, ArchUnit is the natural replacement and needs
 * a dependency decision first.
 */
class ModuleDependencyRulesTest {

    /** Types that make a module framework-, storage- or engine-coupled. */
    private static final List<String> SPRING = List.of("org.springframework.");
    private static final List<String> PERSISTENCE = List.of("jakarta.persistence.", "org.hibernate.", "javax.persistence.");
    private static final List<String> WEB = List.of("jakarta.servlet.", "io.swagger.");
    private static final List<String> KUBERNETES = List.of("io.kubernetes.", "io.fabric8.");
    private static final List<String> SERIALIZATION = List.of("com.fasterxml.jackson.");
    private static final List<String> ENGINE_SPECIFIC = List.of("org.apache.jmeter.", "freemarker.");

    /** V1 packages that live in platform-core. No inner module may depend on the composition root. */
    private static final List<String> PLATFORM_CORE_V1 = List.of(
            "com.perfplatform.engine.TestRunService",
            "com.perfplatform.engine.JmeterTestOrchestrator",
            "com.perfplatform.storage.",
            "com.perfplatform.config.Team",
            "com.perfplatform.sla.",
            "com.perfplatform.report.",
            "com.perfplatform.analysis.",
            "com.perfplatform.notification.",
            "com.perfplatform.security.",
            "com.perfplatform.api.PlatformController");

    private static Path repositoryRoot() {
        Path candidate = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (candidate != null) {
            if (Files.isDirectory(candidate.resolve("platform-domain"))
                    && Files.isRegularFile(candidate.resolve("pom.xml"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException(
                "Could not locate the repository root from " + System.getProperty("user.dir"));
    }

    /**
     * Asserts that no source file in the module imports any forbidden package.
     *
     * @param modulePath  module directory relative to the repository root
     * @param forbidden   forbidden import prefixes
     */
    private void assertNoImports(String modulePath, List<String> forbidden) {
        Path sourceRoot = repositoryRoot().resolve(modulePath).resolve("src/main/java");
        assertThat(sourceRoot)
                .as("module %s must exist and contain sources", modulePath)
                .isDirectory();

        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> collectViolations(path, sourceRoot, forbidden, violations));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        assertThat(violations)
                .as("%s must not depend on these packages — see CLAUDE.md §9", modulePath)
                .isEmpty();
    }

    private void collectViolations(Path file, Path sourceRoot, List<String> forbidden, List<String> violations) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("import ")) {
                continue;
            }
            String imported = trimmed
                    .substring("import ".length())
                    .replaceFirst("^static ", "")
                    .replace(";", "")
                    .trim();
            for (String prefix : forbidden) {
                if (imported.startsWith(prefix)) {
                    violations.add("%s imports %s".formatted(sourceRoot.relativize(file), imported));
                }
            }
        }
    }

    @SafeVarargs
    private static List<String> allOf(List<String>... groups) {
        List<String> combined = new ArrayList<>();
        for (List<String> group : groups) {
            combined.addAll(group);
        }
        return combined;
    }

    @Test
    @DisplayName("platform-domain depends on the JDK only — no Spring, JPA, Jackson, Kubernetes or engine types")
    void domainIsFreeOfInfrastructure() {
        assertNoImports("platform-domain",
                allOf(SPRING, PERSISTENCE, WEB, KUBERNETES, SERIALIZATION, ENGINE_SPECIFIC, PLATFORM_CORE_V1));
    }

    @Test
    @DisplayName("platform-engine-spi depends only on the domain — no Spring, JPA, Kubernetes or engine types")
    void engineSpiIsFreeOfInfrastructure() {
        assertNoImports("platform-engine-spi",
                allOf(SPRING, PERSISTENCE, WEB, KUBERNETES, SERIALIZATION, ENGINE_SPECIFIC, PLATFORM_CORE_V1));
    }

    @Test
    @DisplayName("engine-jmeter goes through the SPI and never touches Spring, persistence or run state")
    void jmeterEngineIsIsolated() {
        assertNoImports("engines/engine-jmeter",
                allOf(SPRING, PERSISTENCE, WEB, KUBERNETES, PLATFORM_CORE_V1));
    }

    @Test
    @DisplayName("engine-playwright goes through the SPI and never touches Spring, persistence or run state")
    void playwrightEngineIsIsolated() {
        assertNoImports("engines/engine-playwright",
                allOf(SPRING, PERSISTENCE, WEB, KUBERNETES, PLATFORM_CORE_V1));
    }

    @Test
    @DisplayName("platform-results stays deterministic — no Spring, JPA or Kubernetes")
    void resultsIsFreeOfInfrastructure() {
        assertNoImports("platform-results",
                allOf(SPRING, PERSISTENCE, WEB, KUBERNETES, ENGINE_SPECIFIC, PLATFORM_CORE_V1));
    }

    @Test
    @DisplayName("platform-orchestration reaches engines and runners only through interfaces")
    void orchestrationDoesNotBypassPorts() {
        assertNoImports("platform-orchestration",
                allOf(PERSISTENCE, KUBERNETES, ENGINE_SPECIFIC, PLATFORM_CORE_V1));
    }

    @Test
    @DisplayName("platform-api never reaches persistence or a Kubernetes client directly")
    void apiDoesNotBypassOrchestration() {
        assertNoImports("platform-api",
                allOf(PERSISTENCE, KUBERNETES, ENGINE_SPECIFIC, PLATFORM_CORE_V1));
    }

    @Test
    @DisplayName("platform-persistence owns storage concerns and does not reach a Kubernetes client")
    void persistenceStaysAStorageAdapter() {
        assertNoImports("platform-persistence",
                allOf(KUBERNETES, ENGINE_SPECIFIC, PLATFORM_CORE_V1));
    }

    /**
     * An importer reads a document and returns a draft. Nothing else.
     *
     * <p>No Spring, so it is testable without a context. No persistence, because an importer creates
     * nothing — the draft it returns is deliberately not stored until someone acts on it. No engine
     * types, because what a source describes is a request, not a JMeter sampler; the same import must
     * serve a browser engine later.
     *
     * <p>{@code SERIALIZATION} is deliberately <em>not</em> forbidden, and importer-openapi holds
     * Jackson because of it. Reading a published document is the whole of an importer's job, and a
     * format library is what an adapter is for; the rule that matters — that the <em>domain</em>
     * stays free of serialization types, so its shape is never dictated by a wire format — is
     * asserted separately against platform-domain.
     *
     * <p>The rule this cannot express, and which matters most, is that an importer executes nothing:
     * a cURL command is parsed, never run. That is asserted in the importer's own tests, where the
     * absence of interpretation is observable.
     */
    @Test
    @DisplayName("an importer depends on the domain only — no Spring, storage or engine types")
    void importersAreIsolated() {
        assertNoImports("platform-importers/importer-curl",
                allOf(SPRING, PERSISTENCE, WEB, KUBERNETES, ENGINE_SPECIFIC, PLATFORM_CORE_V1));
        assertNoImports("platform-importers/importer-openapi",
                allOf(SPRING, PERSISTENCE, WEB, KUBERNETES, ENGINE_SPECIFIC, PLATFORM_CORE_V1));
        assertNoImports("platform-importers/importer-har",
                allOf(SPRING, PERSISTENCE, WEB, KUBERNETES, ENGINE_SPECIFIC, PLATFORM_CORE_V1));
        assertNoImports("platform-importers/importer-postman",
                allOf(SPRING, PERSISTENCE, WEB, KUBERNETES, ENGINE_SPECIFIC, PLATFORM_CORE_V1));
    }

    /**
     * The one module permitted a Kubernetes client, and permitted nothing else.
     *
     * <p>It knows how to start, observe and stop a runner; it must not know what the runner does. No
     * engine types, so a second engine reuses it unchanged. No Spring, so it is testable without a
     * context. No persistence, because a provider reports and the control plane decides — a provider
     * that could write run state could mark its own failures as successes.
     */
    @Test
    @DisplayName("runner-kubernetes may hold a Kubernetes client, but no engine, Spring or storage")
    void kubernetesRunnerIsIsolated() {
        assertNoImports("runners/runner-kubernetes",
                allOf(SPRING, PERSISTENCE, WEB, ENGINE_SPECIFIC, SERIALIZATION, PLATFORM_CORE_V1));
    }
}
