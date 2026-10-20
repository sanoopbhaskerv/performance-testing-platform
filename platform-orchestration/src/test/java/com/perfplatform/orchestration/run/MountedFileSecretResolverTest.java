package com.perfplatform.orchestration.run;

import com.perfplatform.domain.port.SecretResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MountedFileSecretResolverTest {

    @TempDir
    Path root;

    @Test
    @DisplayName("resolves a k8s secret reference from mounted files without exposing the value")
    void resolvesMountedSecretFile() throws Exception {
        Path secret = root.resolve("session-broker-client");
        Files.createDirectories(secret);
        Files.writeString(secret.resolve("token"), "broker-client-token\n", StandardCharsets.UTF_8);

        MountedFileSecretResolver resolver = new MountedFileSecretResolver(root);

        SecretResolver.SecretMaterial material =
                resolver.resolve("k8s://session-broker-client/token");

        assertThat(material.reference()).isEqualTo("k8s://session-broker-client/token");
        assertThat(material.value()).isEqualTo("broker-client-token");
        assertThat(material.toString()).doesNotContain("broker-client-token");
    }

    @Test
    @DisplayName("supports namespace-qualified mounted secret references")
    void resolvesNamespaceQualifiedReference() throws Exception {
        Path secret = root.resolve("perf-platform").resolve("session-broker-client");
        Files.createDirectories(secret);
        Files.writeString(secret.resolve("token"), "broker-client-token", StandardCharsets.UTF_8);

        MountedFileSecretResolver resolver = new MountedFileSecretResolver(root);

        assertThat(resolver.resolve("k8s://perf-platform/session-broker-client/token").value())
                .isEqualTo("broker-client-token");
    }

    @Test
    @DisplayName("rejects unsupported schemes and path traversal")
    void rejectsUnsafeReferences() {
        MountedFileSecretResolver resolver = new MountedFileSecretResolver(root);

        assertThatThrownBy(() -> resolver.resolve("vault://session-broker/client"))
                .isInstanceOf(SecretResolver.SecretResolutionException.class)
                .hasMessageContaining("supports only k8s://");

        assertThatThrownBy(() -> resolver.resolve("k8s://session-broker/../token"))
                .isInstanceOf(SecretResolver.SecretResolutionException.class)
                .hasMessageContaining("path traversal");
    }
}
