package com.perfplatform.engine.spi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

class EngineCapabilitiesTest {

    private EngineCapabilities capabilities(String runnerImage) {
        return new EngineCapabilities(
                "jmeter",
                Set.of(WorkloadKind.PROTOCOL),
                Set.of("openapi"),
                Set.of("stages"),
                500,
                true,
                true,
                runnerImage);
    }

    @Test
    @DisplayName("a workload kind check answers for the declared kinds only")
    void answersSupportedWorkloadKinds() {
        EngineCapabilities capabilities = capabilities("justb4/jmeter:5.6.2");

        assertThat(capabilities.supports(WorkloadKind.PROTOCOL)).isTrue();
        assertThat(capabilities.supports(WorkloadKind.BROWSER)).isFalse();
    }

    /**
     * An unpinned image means two shards of one run can execute different engine builds, which
     * invalidates the merged result and makes the run irreproducible from its plan digest.
     * Rejecting it at construction keeps the rule from depending on review discipline.
     */
    @Test
    @DisplayName("a runner image with no tag is rejected")
    void rejectsUnpinnedRunnerImage() {
        assertThatThrownBy(() -> capabilities("justb4/jmeter"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be pinned");
    }

    @Test
    @DisplayName("a runner image tagged 'latest' is rejected")
    void rejectsLatestTag() {
        assertThatThrownBy(() -> capabilities("justb4/jmeter:latest"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not use the 'latest' tag");
    }

    @Test
    @DisplayName("a registry host with a port is not mistaken for a tag")
    void acceptsRegistryPortWithExplicitTag() {
        assertThatCode(() -> capabilities("registry.internal:5000/perf/jmeter:5.6.2"))
                .doesNotThrowAnyException();

        // Same registry, no tag on the image itself — the port must not satisfy the tag check.
        assertThatThrownBy(() -> capabilities("registry.internal:5000/perf/jmeter"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be pinned");
    }

    @Test
    @DisplayName("a digest-pinned image is accepted")
    void acceptsDigestPinnedImage() {
        assertThatCode(() -> capabilities(
                "registry.internal/perf/jmeter@sha256:0123456789abcdef0123456789abcdef"
                        + "0123456789abcdef0123456789abcdef"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an engine must declare at least one workload kind")
    void rejectsEmptyWorkloadKinds() {
        assertThatThrownBy(() -> new EngineCapabilities(
                "jmeter", Set.of(), Set.of("openapi"), Set.of("stages"),
                500, true, true, "justb4/jmeter:5.6.2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one kind");
    }

    @Test
    @DisplayName("a non-positive concurrency ceiling is rejected")
    void rejectsNonPositiveConcurrency() {
        assertThatThrownBy(() -> new EngineCapabilities(
                "jmeter", Set.of(WorkloadKind.PROTOCOL), Set.of("openapi"), Set.of("stages"),
                0, true, true, "justb4/jmeter:5.6.2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be positive");
    }

    @Test
    @DisplayName("a blank engine id is rejected")
    void rejectsBlankEngineId() {
        assertThatThrownBy(() -> new EngineCapabilities(
                " ", Set.of(WorkloadKind.PROTOCOL), Set.of("openapi"), Set.of("stages"),
                500, true, true, "justb4/jmeter:5.6.2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("engineId");
    }

    @Test
    @DisplayName("declared sets are defensively copied and unmodifiable")
    void defensivelyCopiesDeclaredSets() {
        Set<String> sources = new LinkedHashSet<>(Set.of("openapi"));
        EngineCapabilities capabilities = new EngineCapabilities(
                "jmeter", Set.of(WorkloadKind.PROTOCOL), sources, Set.of("stages"),
                500, true, true, "justb4/jmeter:5.6.2");

        sources.add("postman");

        assertThat(capabilities.supportedSourceTypes()).containsExactly("openapi");
        assertThatThrownBy(() -> capabilities.supportedSourceTypes().add("har"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
