package com.perfplatform.runner.kubernetes;

import com.perfplatform.domain.identity.ApplicationId;
import com.perfplatform.domain.identity.RecorderSessionId;
import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.recorder.RecorderSessionPolicy;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class RecorderSessionManifestsTest {

    private static final RecorderCellSettings ENFORCED_SETTINGS = new RecorderCellSettings(
            "perf-recorders",
            "perf-recorder",
            "registry.internal/perfplatform/recorder:v1.50.0-1",
            "500m", "1",
            "1Gi", "2Gi",
            "2Gi",
            Duration.ofHours(1),
            Duration.ofMinutes(2),
            Duration.ofMinutes(15),
            true);

    private static final RecorderCellSettings LOCAL_UNENFORCED_SETTINGS =
            new RecorderCellSettings(
                    ENFORCED_SETTINGS.namespace(),
                    ENFORCED_SETTINGS.serviceAccount(),
                    ENFORCED_SETTINGS.image(),
                    ENFORCED_SETTINGS.cpuRequest(),
                    ENFORCED_SETTINGS.cpuLimit(),
                    ENFORCED_SETTINGS.memoryRequest(),
                    ENFORCED_SETTINGS.memoryLimit(),
                    ENFORCED_SETTINGS.workspaceSizeLimit(),
                    ENFORCED_SETTINGS.finishedJobTtl(),
                    ENFORCED_SETTINGS.deadlineGrace(),
                    ENFORCED_SETTINGS.idleTimeout(),
                    false);

    @Nested
    @DisplayName("network enforcement")
    class NetworkEnforcement {

        @Test
        @DisplayName("enabled sessions render an egress-only NetworkPolicy from frozen IPs")
        void rendersNetworkPolicyFromFrozenAddresses() throws Exception {
            RecorderSessionRequest request = request();
            RecorderSessionManifests manifests =
                    RecorderSessionManifests.build(request, ENFORCED_SETTINGS);

            assertThat(manifests.networkPolicy()).isPresent();

            NetworkPolicy policy = manifests.networkPolicy().orElseThrow();
            assertThat(policy.getSpec().getPolicyTypes()).containsExactly("Egress");
            assertThat(policy.getSpec().getPodSelector().getMatchLabels())
                    .containsEntry(RecorderSessionManifests.LABEL_RECORDER_SESSION_ID,
                            request.sessionId().value().toString())
                    .containsEntry(RecorderSessionManifests.LABEL_COMPONENT,
                            RecorderSessionManifests.COMPONENT);

            assertThat(policy.getSpec().getEgress()).hasSize(3);
            assertThat(renderedCidrs(policy))
                    .contains("10.10.20.30/32", "10.10.20.31/32", "10.10.20.32/32");
            assertThat(renderedPorts(policy)).contains(443);
        }

        @Test
        @DisplayName("local disabled sessions render no NetworkPolicy and label the Job accordingly")
        void disabledModeSuppressesNetworkPolicy() throws Exception {
            RecorderSessionManifests manifests =
                    RecorderSessionManifests.build(request(), LOCAL_UNENFORCED_SETTINGS);

            assertThat(manifests.networkPolicy()).isEmpty();
            assertThat(manifests.job().getMetadata().getLabels())
                    .containsEntry(RecorderSessionManifests.LABEL_NETWORK_ENFORCEMENT, "disabled");
            assertThat(env(manifests.job()))
                    .containsEntry("PERF_RECORDER_NETWORK_ENFORCEMENT", "false");
        }
    }

    @Nested
    @DisplayName("recorder pod contract")
    class RecorderPodContract {

        @Test
        @DisplayName("the recorder runs tokenless, non-root and without privilege escalation")
        void tokenlessAndRestricted() throws Exception {
            Job job = RecorderSessionManifests.build(request(), ENFORCED_SETTINGS).job();
            Container container = container(job);

            assertThat(job.getSpec().getTemplate().getSpec().getAutomountServiceAccountToken())
                    .isFalse();
            assertThat(job.getSpec().getTemplate().getSpec().getSecurityContext().getRunAsNonRoot())
                    .isTrue();
            assertThat(container.getSecurityContext().getAllowPrivilegeEscalation()).isFalse();
            assertThat(container.getSecurityContext().getReadOnlyRootFilesystem()).isTrue();
            assertThat(container.getSecurityContext().getCapabilities().getDrop())
                    .containsExactly("ALL");
        }

        @Test
        @DisplayName("the recorder is bounded and never retried")
        void boundedAndNeverRetried() throws Exception {
            Job job = RecorderSessionManifests.build(request(), ENFORCED_SETTINGS).job();

            assertThat(job.getSpec().getBackoffLimit()).isZero();
            assertThat(job.getSpec().getTemplate().getSpec().getRestartPolicy()).isEqualTo("Never");
            assertThat(job.getSpec().getActiveDeadlineSeconds())
                    .isEqualTo(Duration.ofMinutes(32).toSeconds());
            assertThat(container(job).getResources().getLimits()).containsKeys("cpu", "memory");
            assertThat(job.getSpec().getTemplate().getSpec().getVolumes().get(0).getEmptyDir()
                    .getSizeLimit()).isNotNull();
        }

        @Test
        @DisplayName("the manifest carries identifiers and policy digest, not credential material")
        void carriesNoSecrets() throws Exception {
            Job job = RecorderSessionManifests.build(request(), ENFORCED_SETTINGS).job();
            String rendered = job.toString().toLowerCase(java.util.Locale.ROOT);

            assertThat(env(job))
                    .containsKeys("PERF_RECORDER_SESSION_ID", "PERF_TARGET_ORIGIN",
                            "PERF_ALLOWED_ORIGINS_DIGEST")
                    .containsEntry("PERF_RECORDER_NETWORK_ENFORCEMENT", "true");
            assertThat(job.getMetadata().getAnnotations())
                    .containsKey(RecorderSessionManifests.ANNOTATION_ALLOWED_ORIGINS_DIGEST);
            assertThat(rendered)
                    .doesNotContain("password")
                    .doesNotContain("authorization")
                    .doesNotContain("bearer ")
                    .doesNotContain("client_secret");
            assertThat(container(job).getArgs()).isNullOrEmpty();
        }
    }

    @Test
    @DisplayName("the recorder image must be pinned")
    void imagePinningIsDetectable() {
        RecorderCellSettings floating = new RecorderCellSettings(
                ENFORCED_SETTINGS.namespace(),
                ENFORCED_SETTINGS.serviceAccount(),
                "registry.internal/perfplatform/recorder:latest",
                ENFORCED_SETTINGS.cpuRequest(),
                ENFORCED_SETTINGS.cpuLimit(),
                ENFORCED_SETTINGS.memoryRequest(),
                ENFORCED_SETTINGS.memoryLimit(),
                ENFORCED_SETTINGS.workspaceSizeLimit(),
                ENFORCED_SETTINGS.finishedJobTtl(),
                ENFORCED_SETTINGS.deadlineGrace(),
                ENFORCED_SETTINGS.idleTimeout(),
                true);

        assertThat(ENFORCED_SETTINGS.hasPinnedImage()).isTrue();
        assertThat(floating.hasPinnedImage()).isFalse();
    }

    private static RecorderSessionRequest request() throws Exception {
        RecorderSessionPolicy policy = RecorderSessionPolicy.forTarget(
                        com.perfplatform.domain.recorder.RecorderOrigin.parse(
                                "https://proposal.perf.internal"),
                        java.util.List.of(ip("10.10.20.30")))
                .allowAssetOrigin("https://static.perf.internal", java.util.List.of(ip("10.10.20.31")))
                .allowLoginOrigin("https://sso.perf.internal", java.util.List.of(ip("10.10.20.32")))
                .build();

        return new RecorderSessionRequest(
                RecorderSessionId.generate(),
                TeamId.generate(),
                ApplicationId.generate(),
                policy,
                Duration.ofMinutes(30));
    }

    private static Container container(Job job) {
        return job.getSpec().getTemplate().getSpec().getContainers().get(0);
    }

    private static Map<String, String> env(Job job) {
        return container(job).getEnv().stream()
                .collect(Collectors.toMap(EnvVar::getName, EnvVar::getValue));
    }

    private static java.util.List<String> renderedCidrs(NetworkPolicy policy) {
        return policy.getSpec().getEgress().stream()
                .flatMap(rule -> rule.getTo().stream())
                .map(peer -> peer.getIpBlock().getCidr())
                .toList();
    }

    private static java.util.List<Integer> renderedPorts(NetworkPolicy policy) {
        return policy.getSpec().getEgress().stream()
                .flatMap(rule -> rule.getPorts().stream())
                .map(port -> port.getPort().getIntVal())
                .toList();
    }

    private static InetAddress ip(String value) throws Exception {
        return InetAddress.getByName(value);
    }
}
