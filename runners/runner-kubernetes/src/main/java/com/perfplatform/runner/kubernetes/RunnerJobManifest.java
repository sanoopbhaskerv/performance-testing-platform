package com.perfplatform.runner.kubernetes;

import com.perfplatform.domain.execution.ProvisionRequest;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.KeyToPathBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.api.model.VolumeMountBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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

    /**
     * How often a runner publishes a progress snapshot.
     *
     * <p>Five seconds, matching the local provider so a live view behaves the same whichever
     * provider a deployment runs. Short enough that a person watching sees a stage change while it
     * is happening; long enough that reducing the results file again is never what keeps a
     * generator's core busy — which would make the measurement affect the measurement.
     */
    static final int PROGRESS_INTERVAL_SECONDS = 5;

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
    private static final String SESSION_FILES_VOLUME = "session-files";

    private RunnerJobManifest() {
    }

    /** The Job name for a shard. Stable for a given shard, so reconciliation can find it again. */
    public static String nameFor(ProvisionRequest request) {
        String name = "perf-%s-%d".formatted(
                shortId(request.runId().value().toString()), request.shardIndex());
        return name.length() <= MAX_NAME_LENGTH ? name : name.substring(0, MAX_NAME_LENGTH);
    }

    /** Run-scoped credential Secret name for this shard. */
    public static String runtimeCredentialSecretNameFor(ProvisionRequest request) {
        return nameFor(request) + "-auth";
    }

    /** Run-scoped mounted session-file Secret name for this shard. */
    public static String sessionFileSecretNameFor(ProvisionRequest request) {
        return nameFor(request) + "-sessions";
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
                            .withVolumes(volumes(request, settings))
                            .addNewContainer()
                                .withName(COMPONENT)
                                .withImage(settings.imageFor(request.engine()))
                                .withImagePullPolicy("IfNotPresent")
                                .withEnv(environment(request, settings))
                                // A Secret referenced by name, whose values kubelet injects at
                                // start. The credential never passes through the control plane, the
                                // manifest, or any log — the platform holds a reference, exactly as
                                // a specification holds a secretRef and never a secret
                                // (CLAUDE.md §20). Absent entirely when the runner's own identity
                                // suffices, which is the case on EKS with real S3.
                                .withEnvFrom(credentialsFrom(settings))
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
                                .withVolumeMounts(volumeMounts(request))
                            .endContainer()
                        .endSpec()
                    .endTemplate()
                .endSpec()
                .build();
    }

    /**
     * The Secret holding storage credentials, if this cell needs one.
     *
     * <p>{@code optional} is deliberately left false: a missing Secret must stop the Pod from
     * starting. A runner that started without credentials would fail minutes later while trying to
     * upload, having already generated its load — the worst possible moment to discover it.
     */
    private static java.util.List<io.fabric8.kubernetes.api.model.EnvFromSource> credentialsFrom(
            RunnerCellSettings settings) {

        if (!settings.artifacts().requiresCredentialsSecret()) {
            return java.util.List.of();
        }
        return java.util.List.of(new io.fabric8.kubernetes.api.model.EnvFromSourceBuilder()
                .withNewSecretRef()
                    .withName(settings.artifacts().credentialsSecret())
                    .withOptional(Boolean.FALSE)
                .endSecretRef()
                .build());
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
            ProvisionRequest request, RunnerCellSettings settings) {

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
        // This shard's dataset partitions, as `fileName=objectKey` pairs. Keys, not content: a
        // dataset is business data and a Job manifest is readable by anyone with cluster access.
        // Already sliced, so a runner never decides which rows it sends.
        env.put("PERF_DATASETS", datasetsOf(request));
        // Secret files are mounted into the workspace by the provider. Names only: values never
        // enter the Job's environment, labels, annotations or command arguments.
        env.put("PERF_SESSION_FILES", sessionFilesOf(request));
        // The labels the platform will read results back under. Objectives are scoped by workload
        // and by scenario, so output labelled any other way could not be judged.
        env.put("PERF_OPERATION_LABELS", String.join(",", request.operationLabels()));
        env.put("PERF_DEADLINE_SECONDS", String.valueOf(request.deadline().toSeconds()));
        // How often the runner publishes what it has measured so far, into its own artifact prefix.
        // The control plane reads and merges those snapshots; without them a run in flight shows a
        // state and nothing else. Zero turns publishing off, which is the escape hatch for a run
        // large enough that re-reducing its results file is a cost worth avoiding.
        env.put("PERF_PROGRESS_INTERVAL_SECONDS",
                String.valueOf(PROGRESS_INTERVAL_SECONDS));

        // Where to read the plan from and write results to. Coordinates only — a runner
        // authenticates with its own projected service-account token, and a credential passed
        // through a Job manifest would end up in an audit log.
        RunnerCellSettings.ArtifactStorage artifacts = settings.artifacts();
        env.put("PERF_ARTIFACT_BUCKET", artifacts.bucket());
        if (!artifacts.region().isBlank()) {
            env.put("PERF_ARTIFACT_REGION", artifacts.region());
        }
        if (!artifacts.endpoint().isBlank()) {
            env.put("PERF_ARTIFACT_ENDPOINT", artifacts.endpoint());
        }
        env.put("PERF_ARTIFACT_PATH_STYLE", String.valueOf(artifacts.pathStyleAccess()));

        // The prefix this shard writes under, so the runner never constructs a key of its own. Key
        // layout is what makes authorization structural; a runner that built its own path could
        // write into another team's prefix.
        env.put("PERF_ARTIFACT_PREFIX", "teams/%s/runs/%s/shards/%s/".formatted(
                request.teamId().value(), request.runId().value(), request.shardId().value()));

        java.util.List<io.fabric8.kubernetes.api.model.EnvVar> rendered = env.entrySet().stream()
                .map(entry -> new EnvVarBuilder()
                        .withName(entry.getKey())
                        .withValue(entry.getValue())
                        .build())
                .toList();
        if (request.runtimeCredentials().isEmpty()) {
            return rendered;
        }

        java.util.List<io.fabric8.kubernetes.api.model.EnvVar> withCredentials =
                new java.util.ArrayList<>(rendered);
        request.runtimeCredentials().forEach(credential -> withCredentials.add(new EnvVarBuilder()
                .withName(credential.environmentVariable())
                .withNewValueFrom()
                    .withNewSecretKeyRef()
                        .withName(runtimeCredentialSecretNameFor(request))
                        .withKey(credential.environmentVariable())
                        .withOptional(Boolean.FALSE)
                    .endSecretKeyRef()
                .endValueFrom()
                .build()));
        return java.util.List.copyOf(withCredentials);
    }

    /**
     * This shard's dataset partitions, as {@code fileName=objectKey} separated by commas.
     *
     * <p>A flat string because environment is a flat string, and the two characters it uses are
     * both impossible in either half: a file name is a plain name validated by
     * {@code DatasetDelivery}, and an object key is built from identifiers and validated names.
     * Empty when the test declares no dataset, which is the common case.
     */
    private static String datasetsOf(ProvisionRequest request) {
        return request.datasets().stream()
                .map(dataset -> dataset.fileName() + "=" + dataset.objectKey())
                .collect(java.util.stream.Collectors.joining(","));
    }

    private static String sessionFilesOf(ProvisionRequest request) {
        return request.secretFiles().stream()
                .map(com.perfplatform.domain.execution.SecretFileDelivery::fileName)
                .collect(java.util.stream.Collectors.joining(","));
    }

    private static List<Volume> volumes(ProvisionRequest request, RunnerCellSettings settings) {
        List<Volume> volumes = new ArrayList<>();
        volumes.add(new VolumeBuilder()
                .withName(WORKSPACE_VOLUME)
                // Temporary and pod-local by construction. Results become durable by being uploaded
                // to object storage, never by living here.
                .withNewEmptyDir()
                    .withSizeLimit(new Quantity(settings.workspaceSizeLimit()))
                .endEmptyDir()
                .build());

        if (!request.secretFiles().isEmpty()) {
            List<io.fabric8.kubernetes.api.model.KeyToPath> items = request.secretFiles().stream()
                    .map(file -> new KeyToPathBuilder()
                            .withKey(file.fileName())
                            .withPath(file.fileName())
                            .build())
                    .toList();
            volumes.add(new VolumeBuilder()
                    .withName(SESSION_FILES_VOLUME)
                    .withNewSecret()
                        .withSecretName(sessionFileSecretNameFor(request))
                        .withItems(items)
                    .endSecret()
                    .build());
        }
        return List.copyOf(volumes);
    }

    private static List<VolumeMount> volumeMounts(ProvisionRequest request) {
        List<VolumeMount> mounts = new ArrayList<>();
        mounts.add(new VolumeMountBuilder()
                .withName(WORKSPACE_VOLUME)
                .withMountPath(WORKSPACE_PATH)
                .build());

        request.secretFiles().forEach(file -> mounts.add(new VolumeMountBuilder()
                .withName(SESSION_FILES_VOLUME)
                .withMountPath(WORKSPACE_PATH + "/" + file.fileName())
                .withSubPath(file.fileName())
                .withReadOnly(Boolean.TRUE)
                .build()));
        return List.copyOf(mounts);
    }

    private static String shortId(String uuid) {
        return uuid.substring(0, RUN_ID_PREFIX);
    }
}
