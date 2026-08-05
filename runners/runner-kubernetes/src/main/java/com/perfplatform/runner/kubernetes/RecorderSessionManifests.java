package com.perfplatform.runner.kubernetes;

import com.perfplatform.domain.recorder.RecorderOrigin;
import com.perfplatform.domain.recorder.RecorderSessionPolicy;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.LabelSelectorBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import io.fabric8.kubernetes.api.model.VolumeMountBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicy;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyEgressRule;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyEgressRuleBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyPeerBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyPortBuilder;

import java.net.InetAddress;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Builds the Kubernetes objects for one recorder session.
 *
 * <p>When network enforcement is enabled, the generated NetworkPolicy selects the recorder Pod and
 * lists only the frozen destination IPs and ports in the session policy. Kubernetes NetworkPolicy
 * cannot express hostnames; hostname decisions have already happened in the domain layer. The
 * runtime that launches the browser must therefore use the same frozen host-to-address mapping, or
 * an explicitly approved DNS exception, rather than relying on open pod DNS egress.
 */
public record RecorderSessionManifests(Job job, Optional<NetworkPolicy> networkPolicy) {

    private static final int MAX_NAME_LENGTH = 63;
    private static final int SESSION_ID_PREFIX = 8;

    public static final String LABEL_MANAGED_BY = RunnerJobManifest.LABEL_MANAGED_BY;
    public static final String LABEL_COMPONENT = RunnerJobManifest.LABEL_COMPONENT;
    public static final String LABEL_TEAM_ID = RunnerJobManifest.LABEL_TEAM_ID;
    public static final String LABEL_RECORDER_SESSION_ID =
            "perfplatform.ameriprise.com/recorder-session-id";
    public static final String LABEL_APPLICATION_ID =
            "perfplatform.ameriprise.com/application-id";
    public static final String LABEL_NETWORK_ENFORCEMENT =
            "perfplatform.ameriprise.com/network-enforcement";
    public static final String ANNOTATION_ALLOWED_ORIGINS_DIGEST =
            "perfplatform.ameriprise.com/allowed-origins-digest";

    public static final String MANAGED_BY = RunnerJobManifest.MANAGED_BY;
    public static final String COMPONENT = "recorder";

    private static final String WORKSPACE_PATH = "/workspace";
    private static final String WORKSPACE_VOLUME = "workspace";

    public static RecorderSessionManifests build(RecorderSessionRequest request,
            RecorderCellSettings settings) {
        Job job = buildJob(request, settings);
        Optional<NetworkPolicy> policy = settings.networkEnforcementEnabled()
                ? Optional.of(buildNetworkPolicy(request, settings))
                : Optional.empty();
        return new RecorderSessionManifests(job, policy);
    }

    public static String nameFor(RecorderSessionRequest request) {
        String name = "perf-rec-%s".formatted(
                request.sessionId().value().toString().substring(0, SESSION_ID_PREFIX));
        return name.length() <= MAX_NAME_LENGTH ? name : name.substring(0, MAX_NAME_LENGTH);
    }

    public static String networkPolicyNameFor(RecorderSessionRequest request) {
        String name = nameFor(request) + "-egress";
        return name.length() <= MAX_NAME_LENGTH ? name : name.substring(0, MAX_NAME_LENGTH);
    }

    private static Job buildJob(RecorderSessionRequest request, RecorderCellSettings settings) {
        long deadlineSeconds = request.deadline().plus(settings.deadlineGrace()).toSeconds();

        return new JobBuilder()
                .withNewMetadata()
                    .withName(nameFor(request))
                    .withNamespace(settings.namespace())
                    .withLabels(labels(request, settings))
                    .withAnnotations(annotations(request))
                .endMetadata()
                .withNewSpec()
                    .withBackoffLimit(0)
                    .withActiveDeadlineSeconds(deadlineSeconds)
                    .withTtlSecondsAfterFinished((int) settings.finishedJobTtl().toSeconds())
                    .withNewTemplate()
                        .withNewMetadata()
                            .withLabels(labels(request, settings))
                            .withAnnotations(annotations(request))
                        .endMetadata()
                        .withNewSpec()
                            .withRestartPolicy("Never")
                            .withServiceAccountName(settings.serviceAccount())
                            .withAutomountServiceAccountToken(Boolean.FALSE)
                            .withNewSecurityContext()
                                .withRunAsNonRoot(Boolean.TRUE)
                                .withRunAsUser(10002L)
                                .withRunAsGroup(10002L)
                                .withFsGroup(10002L)
                                .withNewSeccompProfile()
                                    .withType("RuntimeDefault")
                                .endSeccompProfile()
                            .endSecurityContext()
                            .withVolumes(new VolumeBuilder()
                                    .withName(WORKSPACE_VOLUME)
                                    .withNewEmptyDir()
                                        .withSizeLimit(new Quantity(settings.workspaceSizeLimit()))
                                    .endEmptyDir()
                                    .build())
                            .addNewContainer()
                                .withName(COMPONENT)
                                .withImage(settings.image())
                                .withImagePullPolicy("IfNotPresent")
                                .withEnv(environment(request, settings))
                                .withNewResources()
                                    .withRequests(Map.of(
                                            "cpu", new Quantity(settings.cpuRequest()),
                                            "memory", new Quantity(settings.memoryRequest())))
                                    .withLimits(Map.of(
                                            "cpu", new Quantity(settings.cpuLimit()),
                                            "memory", new Quantity(settings.memoryLimit())))
                                .endResources()
                                .withNewSecurityContext()
                                    .withAllowPrivilegeEscalation(Boolean.FALSE)
                                    .withReadOnlyRootFilesystem(Boolean.TRUE)
                                    .withRunAsNonRoot(Boolean.TRUE)
                                    .withNewCapabilities()
                                        .withDrop("ALL")
                                    .endCapabilities()
                                .endSecurityContext()
                                .withVolumeMounts(new VolumeMountBuilder()
                                        .withName(WORKSPACE_VOLUME)
                                        .withMountPath(WORKSPACE_PATH)
                                        .build())
                            .endContainer()
                        .endSpec()
                    .endTemplate()
                .endSpec()
                .build();
    }

    private static NetworkPolicy buildNetworkPolicy(RecorderSessionRequest request,
            RecorderCellSettings settings) {
        return new NetworkPolicyBuilder()
                .withNewMetadata()
                    .withName(networkPolicyNameFor(request))
                    .withNamespace(settings.namespace())
                    .withLabels(labels(request, settings))
                    .withAnnotations(annotations(request))
                .endMetadata()
                .withNewSpec()
                    .withPodSelector(new LabelSelectorBuilder()
                            .withMatchLabels(selectorLabels(request))
                            .build())
                    .withPolicyTypes("Egress")
                    .withEgress(egressRules(request.policy()))
                .endSpec()
                .build();
    }

    private static List<NetworkPolicyEgressRule> egressRules(RecorderSessionPolicy policy) {
        return policy.allowedOrigins().values().stream()
                .sorted(Comparator.comparing(allowed -> allowed.origin().value()))
                .map(RecorderSessionManifests::egressRule)
                .toList();
    }

    private static NetworkPolicyEgressRule egressRule(
            RecorderSessionPolicy.AllowedOrigin allowed) {
        List<io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyPeer> peers =
                allowed.resolvedAddresses().stream()
                        .sorted(Comparator.comparing(InetAddress::getHostAddress))
                        .map(address -> new NetworkPolicyPeerBuilder()
                                .withNewIpBlock()
                                    .withCidr(hostCidr(address))
                                .endIpBlock()
                                .build())
                        .toList();

        return new NetworkPolicyEgressRuleBuilder()
                .withTo(peers)
                .withPorts(new NetworkPolicyPortBuilder()
                        .withProtocol("TCP")
                        .withPort(new IntOrString(allowed.origin().port()))
                        .build())
                .build();
    }

    private static Map<String, String> labels(RecorderSessionRequest request,
            RecorderCellSettings settings) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(LABEL_MANAGED_BY, MANAGED_BY);
        labels.put(LABEL_COMPONENT, COMPONENT);
        labels.put(LABEL_RECORDER_SESSION_ID, request.sessionId().value().toString());
        labels.put(LABEL_TEAM_ID, request.teamId().value().toString());
        labels.put(LABEL_APPLICATION_ID, request.applicationId().value().toString());
        labels.put(LABEL_NETWORK_ENFORCEMENT,
                settings.networkEnforcementEnabled() ? "enabled" : "disabled");
        return labels;
    }

    private static Map<String, String> selectorLabels(RecorderSessionRequest request) {
        return Map.of(
                LABEL_MANAGED_BY, MANAGED_BY,
                LABEL_COMPONENT, COMPONENT,
                LABEL_RECORDER_SESSION_ID, request.sessionId().value().toString());
    }

    private static Map<String, String> annotations(RecorderSessionRequest request) {
        return Map.of(ANNOTATION_ALLOWED_ORIGINS_DIGEST, allowedOriginsDigest(request.policy()));
    }

    private static List<io.fabric8.kubernetes.api.model.EnvVar> environment(
            RecorderSessionRequest request, RecorderCellSettings settings) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("PERF_RECORDER_SESSION_ID", request.sessionId().value().toString());
        env.put("PERF_TEAM_ID", request.teamId().value().toString());
        env.put("PERF_APPLICATION_ID", request.applicationId().value().toString());
        env.put("PERF_TARGET_ORIGIN", request.policy().registeredTargetOrigin().value());
        env.put("PERF_ALLOWED_ORIGINS", allowedOrigins(request.policy()));
        env.put("PERF_ALLOWED_ORIGINS_DIGEST", allowedOriginsDigest(request.policy()));
        env.put("PERF_RECORDER_NETWORK_ENFORCEMENT",
                String.valueOf(settings.networkEnforcementEnabled()));
        env.put("PERF_RECORDER_IDLE_TIMEOUT_SECONDS",
                String.valueOf(settings.idleTimeout().toSeconds()));
        env.put("PERF_RECORDER_DEADLINE_SECONDS", String.valueOf(request.deadline().toSeconds()));
        env.put("PERF_WORKSPACE", WORKSPACE_PATH);

        return env.entrySet().stream()
                .map(entry -> new EnvVarBuilder()
                        .withName(entry.getKey())
                        .withValue(entry.getValue())
                        .build())
                .toList();
    }

    private static String allowedOrigins(RecorderSessionPolicy policy) {
        return policy.allowedOrigins().values().stream()
                .sorted(Comparator.comparing(allowed -> allowed.origin().value()))
                .map(RecorderSessionManifests::describeAllowedOrigin)
                .collect(Collectors.joining(","));
    }

    private static String describeAllowedOrigin(RecorderSessionPolicy.AllowedOrigin allowed) {
        String addresses = allowed.resolvedAddresses().stream()
                .map(InetAddress::getHostAddress)
                .sorted()
                .collect(Collectors.joining("+"));
        RecorderOrigin origin = allowed.origin();
        return origin.value() + "=" + allowed.purpose() + ":" + addresses;
    }

    private static String allowedOriginsDigest(RecorderSessionPolicy policy) {
        String canonical = allowedOrigins(policy);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return "sha256:" + HexFormat.of().formatHex(
                    digest.digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String hostCidr(InetAddress address) {
        return address.getHostAddress() + "/" + (address.getAddress().length == 4 ? "32" : "128");
    }

    public Container recorderContainer() {
        return job.getSpec().getTemplate().getSpec().getContainers().get(0);
    }
}
