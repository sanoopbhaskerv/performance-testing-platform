package com.perfplatform.runner.kubernetes;

import com.perfplatform.domain.execution.ProvisionRequest;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Builds the Kubernetes Job that runs one shard.
 *
 * <p>Pure: a request and the cell's settings in, a manifest out. Nothing here talks to a cluster, so
 * every guarantee below is contract-testable without one — which is what makes them guarantees
 * rather than intentions.
 *
 * <h2>The safety properties this encodes</h2>
 * <ul>
 *   <li><b>{@code backoffLimit: 0} and {@code restartPolicy: Never}.</b> The most important line in
 *       the file. Kubernetes' default is to retry a failed Pod, and a retried performance test is a
 *       second load test against a real system plus two overlapping sets of results. A failed run is
 *       surfaced, never replayed (CLAUDE.md §16).</li>
 *   <li><b>{@code activeDeadlineSeconds}.</b> A hard stop the cluster enforces. A workload that
 *       ignores its own stages — a hung pool, a runaway loop — must not keep generating load
 *       because the control plane happened to be unreachable.</li>
 *   <li><b>Non-root, read-only root filesystem, no privilege escalation, all capabilities dropped,
 *       runtime-default seccomp.</b> A runner executes a plan compiled from a user's document; it is
 *       the least trusted thing in the platform and is confined accordingly.</li>
 *   <li><b>No secret values anywhere.</b> Not in labels, annotations, environment or command
 *       arguments. Manifests are readable by anyone with cluster access, appear in audit logs, and
 *       get pasted into tickets. Secrets reach a runner through its own service identity.</li>
 *   <li><b>Explicit requests and limits.</b> An unbounded load generator is itself a denial of
 *       service against the cluster it runs in.</li>
 * </ul>
 *
 * <h2>Naming</h2>
 * Deterministic enough for reconciliation to find a Job again, short enough for Kubernetes' 63
 * character limit, and unique per shard. The run's own UUID is truncated: the full identifier lives
 * in a label, which is what queries actually use.
 */
public final class RunnerJobManifest {

    /** Kubernetes object names cannot exceed this. */
    private static final int MAX_NAME_LENGTH = 63;

    /** How much of a run identifier appears in a Job name. Enough to be unambiguous by eye. */
    private static final int RUN_ID_PREFIX = 8;

    // Labels are how reconciliation finds a run's Jobs after a control-plane restart, so they are
    // part of the contract rather than decoration.
    public static final String LABEL_MANAGED_BY = "app.kubernetes.io/managed-by";
    public static final String LABEL_COMPONENT = "app.kubernetes.io/component";
    public static final String LABEL_RUN_ID = "perfplatform.ameriprise.com/run-id";
    public static final String LABEL_SHARD_ID = "perfplatform.ameriprise.com/shard-id";
    public static final String LABEL_SHARD_INDEX = "perfplatform.ameriprise.com/shard-index";
    public static final String LABEL_TEAM_ID = "perfplatform.ameriprise.com/team-id";
    public static final String LABEL_ENGINE = "perfplatform.ameriprise.com/engine";

    /**
     * The plan digest travels as an annotation, not a label.
     *
     * <p>Labels are limited to 63 characters and {@code sha256:<64 hex>} does not fit. It is also
     * not something anything queries by — it is evidence of which plan this Job was created for.
     */
    public static final String ANNOTATION_PLAN_DIGEST = "perfplatform.ameriprise.com/plan-digest";
    public static final String ANNOTATION_PLAN_ID = "perfplatform.ameriprise.com/plan-id";

    public static final String MANAGED_BY = "performance-testing-platform";
    public static final String COMPONENT = "runner";

    /** Where the runner's temporary workspace is mounted. Pod-local, never a durable reference. */
    private static final String WORKSPACE_PATH = "/workspace";
    private static final String WORKSPACE_VOLUME = "workspace";

    private RunnerJobManifest() {
    }

    /** The Job name for a shard. Stable for a given shard, so reconciliation can find it again. */
    public static String nameFor(ProvisionRequest request) {
        String name = "perf-%s-%d".formatted(
                shortId(request.runId().value().toString()), request.shardIndex());
        return name.length() <= MAX_NAME_LENGTH ? name : name.substring(0, MAX_NAME_LENGTH);
    }

    /** The label selector that finds every Job belonging to one run. */
    public static Map<String, String> selectorForRun(String runId) {
        return Map.of(LABEL_MANAGED_BY, MANAGED_BY, LABEL_RUN_ID, runId);
    }

    /**
     * Builds the Job for one shard.
     *
     * @throws RunnerCellSettings.UnknownRunnerImageException if the engine has no approved image
     */
    public static Job build(ProvisionRequest request, RunnerCellSettings settings) {
        long deadlineSeconds = request.deadline().plus(settings.deadlineGrace()).toSeconds();

        return new JobBuilder()
                .withNewMetadata()
                    .withName(nameFor(request))
                    .withNamespace(settings.namespace())
                    .withLabels(labels(request))
                    .withAnnotations(annotations(request))
                .endMetadata()
                .withNewSpec()
                    // Never retried. See the class javadoc: this single value is what stops a
                    // transient runner failure from becoming a second load test.
                    .withBackoffLimit(0)
                    .withActiveDeadlineSeconds(deadlineSeconds)
                    .withTtlSecondsAfterFinished((int) settings.finishedJobTtl().toSeconds())
                    .withNewTemplate()
                        .withNewMetadata()
                            .withLabels(labels(request))
                            .withAnnotations(annotations(request))
                        .endMetadata()
                        .withNewSpec()
                            .withRestartPolicy("Never")
                            .withServiceAccountName(settings.serviceAccount())
                            .withAutomountServiceAccountToken(Boolean.TRUE)
                            .withNewSecurityContext()
                                .withRunAsNonRoot(Boolean.TRUE)
                                .withRunAsUser(10001L)
                                .withRunAsGroup(10001L)
                                .withFsGroup(10001L)
                                .withNewSeccompProfile()
                                    .withType("RuntimeDefault")
                                .endSeccompProfile()
                            .endSecurityContext()
                            .addNewVolume()
                                .withName(WORKSPACE_VOLUME)
                                // Temporary and pod-local by construction. Results become durable
                                // by being uploaded to object storage, never by living here.
                                .withNewEmptyDir()
                                    .withSizeLimit(new Quantity(settings.workspaceSizeLimit()))
                                .endEmptyDir()
                            .endVolume()
                            .addNewContainer()
                                .withName(COMPONENT)
                                .withImage(settings.imageFor(request.engine()))
                                .withImagePullPolicy("IfNotPresent")
                                .withEnv(environment(request))
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
                                .addNewVolumeMount()
                                    .withName(WORKSPACE_VOLUME)
                                    .withMountPath(WORKSPACE_PATH)
                                .endVolumeMount()
                            .endContainer()
                        .endSpec()
                    .endTemplate()
                .endSpec()
                .build();
    }

    private static Map<String, String> labels(ProvisionRequest request) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(LABEL_MANAGED_BY, MANAGED_BY);
        labels.put(LABEL_COMPONENT, COMPONENT);
        labels.put(LABEL_RUN_ID, request.runId().value().toString());
        labels.put(LABEL_SHARD_ID, request.shardId().value().toString());
        labels.put(LABEL_SHARD_INDEX, String.valueOf(request.shardIndex()));
        labels.put(LABEL_TEAM_ID, request.teamId().value().toString());
        labels.put(LABEL_ENGINE, request.engine().toLowerCase(Locale.ROOT));
        return labels;
    }

    private static Map<String, String> annotations(ProvisionRequest request) {
        Map<String, String> annotations = new LinkedHashMap<>();
        annotations.put(ANNOTATION_PLAN_DIGEST, request.planDigest().value());
        annotations.put(ANNOTATION_PLAN_ID, request.planId().value().toString());
        return annotations;
    }

    /**
     * What the runner is told.
     *
     * <p>Identifiers and keys only. The runner fetches the plan it must execute from the artifact
     * store using {@code PERF_WORKLOAD_KEY} and its own credentials; nothing sensitive is passed in,
     * because environment is visible in a Pod spec to anyone who can read it.
     *
     * <p>{@code PERF_PLAN_DIGEST} is echoed back in the runner manifest. Results whose digest does
     * not match the run's plan are rejected rather than merged — they describe a different test.
     */
    private static java.util.List<io.fabric8.kubernetes.api.model.EnvVar> environment(
            ProvisionRequest request) {

        Map<String, String> env = new LinkedHashMap<>();
        env.put("PERF_RUN_ID", request.runId().value().toString());
        env.put("PERF_TEAM_ID", request.teamId().value().toString());
        env.put("PERF_SHARD_ID", request.shardId().value().toString());
        env.put("PERF_SHARD_INDEX", String.valueOf(request.shardIndex()));
        env.put("PERF_SHARD_COUNT", String.valueOf(request.shardCount()));
        env.put("PERF_ENGINE", request.engine());
        env.put("PERF_PLAN_ID", request.planId().value().toString());
        env.put("PERF_PLAN_DIGEST", request.planDigest().value());
        env.put("PERF_WORKLOAD_KEY", request.workloadKey());
        env.put("PERF_WORKSPACE", WORKSPACE_PATH);
        // The labels the platform will read results back under. Objectives are scoped by workload
        // and by scenario, so output labelled any other way could not be judged.
        env.put("PERF_OPERATION_LABELS", String.join(",", request.operationLabels()));
        env.put("PERF_DEADLINE_SECONDS", String.valueOf(request.deadline().toSeconds()));

        return env.entrySet().stream()
                .map(entry -> new EnvVarBuilder()
                        .withName(entry.getKey())
                        .withValue(entry.getValue())
                        .build())
                .toList();
    }

    private static String shortId(String uuid) {
        return uuid.substring(0, RUN_ID_PREFIX);
    }
}
