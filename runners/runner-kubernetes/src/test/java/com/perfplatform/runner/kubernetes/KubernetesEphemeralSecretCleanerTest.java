package com.perfplatform.runner.kubernetes;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@EnableKubernetesMockClient(crud = true)
class KubernetesEphemeralSecretCleanerTest {

    KubernetesClient client;

    private KubernetesEphemeralSecretCleaner cleaner() {
        return new KubernetesEphemeralSecretCleaner(client, RunnerFixtures.SETTINGS);
    }

    private Secret secretNamed(String name) {
        return client.secrets()
                .inNamespace(RunnerFixtures.SETTINGS.namespace())
                .withName(name)
                .get();
    }

    @Test
    @DisplayName("expired runner Secrets are deleted while unexpired and unannotated ones remain")
    void deletesExpiredRunnerSecretsOnly() {
        createSecret("expired", Map.of(
                KubernetesEphemeralSecretCleaner.ANNOTATION_SECRET_DELETE_AFTER,
                "2026-08-03T10:00:00Z"));
        createSecret("unexpired", Map.of(
                KubernetesEphemeralSecretCleaner.ANNOTATION_SECRET_DELETE_AFTER,
                "2026-08-03T10:01:00Z"));
        createSecret("unannotated", Map.of());
        client.secrets()
                .inNamespace(RunnerFixtures.SETTINGS.namespace())
                .resource(new SecretBuilder()
                        .withNewMetadata()
                            .withName("not-runner-managed")
                            .withNamespace(RunnerFixtures.SETTINGS.namespace())
                        .endMetadata()
                        .withType("Opaque")
                        .withStringData(Map.of("sessions.csv", "still here"))
                        .build())
                .create();

        int deleted = cleaner().deleteExpired(Instant.parse("2026-08-03T10:00:00Z"));

        assertThat(deleted).isEqualTo(1);
        assertThat(secretNamed("expired")).isNull();
        assertThat(secretNamed("unexpired")).isNotNull();
        assertThat(secretNamed("unannotated")).isNotNull();
        assertThat(secretNamed("not-runner-managed")).isNotNull();
    }

    private void createSecret(String name, Map<String, String> annotations) {
        client.secrets()
                .inNamespace(RunnerFixtures.SETTINGS.namespace())
                .resource(new SecretBuilder()
                        .withNewMetadata()
                            .withName(name)
                            .withNamespace(RunnerFixtures.SETTINGS.namespace())
                            .withLabels(Map.of(
                                    RunnerJobManifest.LABEL_MANAGED_BY,
                                    RunnerJobManifest.MANAGED_BY))
                            .withAnnotations(annotations)
                        .endMetadata()
                        .withType("Opaque")
                        .withStringData(Map.of("sessions.csv", "session_id,session_value"))
                        .build())
                .create();
    }
}
