package com.perfplatform.runner.kubernetes;

import com.perfplatform.domain.execution.ProvisionRequest;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Job manifest contract.
 *
 * <p>Every assertion here corresponds to something that goes wrong in production if the manifest is
 * built differently — duplicate load, an unbounded load generator, a secret in an audit log, a
 * runner that outlives its run. The manifest builder is pure, so all of it is verifiable without a
 * cluster, which is what CLAUDE.md §25 asks for before requiring a real one.
 */
class RunnerJobManifestTest {

    private static Job build(ProvisionRequest request) {
        return RunnerJobManifest.build(request, RunnerFixtures.SETTINGS);
    }

    private static Container container(Job job) {
        return job.getSpec().getTemplate().getSpec().getContainers().get(0);
    }

    private static Map<String, String> env(Job job) {
        return container(job).getEnv().stream()
                .collect(Collectors.toMap(EnvVar::getName, EnvVar::getValue));
    }

    @Nested
    @DisplayName("never replaying a material run")
    class NeverRetries {

        /**
         * The single most consequential value in the manifest. Kubernetes' default is to retry a
         * failed Pod; a retried performance test is a second load test against a real system and two
         * overlapping sets of results, and it would look like an ordinary run.
         */
        @Test
        @DisplayName("a failed runner Pod is never retried")
        void backoffLimitIsZero() {
            assertThat(build(RunnerFixtures.request()).getSpec().getBackoffLimit()).isZero();
        }

        @Test
        @DisplayName("a finished runner Pod is never restarted in place")
        void restartPolicyIsNever() {
            assertThat(build(RunnerFixtures.request())
                    .getSpec().getTemplate().getSpec().getRestartPolicy())
                    .isEqualTo("Never");
        }
    }

    @Nested
    @DisplayName("bounded execution")
    class Bounds {

        /**
         * A hard stop the cluster enforces. A workload that ignores its own stages must not keep
         * generating load because the control plane happened to be unreachable.
         */
        @Test
        @DisplayName("the Job carries a deadline covering the plan plus a startup and upload grace")
        void activeDeadlineCoversPlanAndGrace() {
            Job job = build(RunnerFixtures.request());

            long expected = RunnerFixtures.DEADLINE
                    .plus(RunnerFixtures.SETTINGS.deadlineGrace()).toSeconds();
            assertThat(job.getSpec().getActiveDeadlineSeconds()).isEqualTo(expected);
        }

        @Test
        @DisplayName("a finished Job is reclaimed rather than accumulating forever")
        void finishedJobHasTtl() {
            assertThat(build(RunnerFixtures.request()).getSpec().getTtlSecondsAfterFinished())
                    .isEqualTo((int) RunnerFixtures.SETTINGS.finishedJobTtl().toSeconds());
        }

        /** An unbounded load generator is itself a denial of service against its own cluster. */
        @Test
        @DisplayName("cpu and memory are both requested and capped")
        void resourcesAreExplicit() {
            var resources = container(build(RunnerFixtures.request())).getResources();

            assertThat(resources.getRequests()).containsKeys("cpu", "memory");
            assertThat(resources.getLimits()).containsKeys("cpu", "memory");
        }

        @Test
        @DisplayName("the temporary workspace is size-limited, so a runaway file cannot fill the node")
        void workspaceIsBounded() {
            var volume = build(RunnerFixtures.request())
                    .getSpec().getTemplate().getSpec().getVolumes().get(0);

            assertThat(volume.getEmptyDir()).isNotNull();
            assertThat(volume.getEmptyDir().getSizeLimit()).isNotNull();
        }
    }

    @Nested
    @DisplayName("confinement")
    class Confinement {

        /**
         * A runner executes a plan compiled from a user's document. It is the least trusted thing in
         * the platform and is confined accordingly.
         */
        @Test
        @DisplayName("the runner is non-root, unprivileged and cannot escalate")
        void runsUnprivileged() {
            Job job = build(RunnerFixtures.request());
            var pod = job.getSpec().getTemplate().getSpec().getSecurityContext();
            var app = container(job).getSecurityContext();

            assertThat(pod.getRunAsNonRoot()).isTrue();
            assertThat(pod.getRunAsUser()).isNotZero();
            assertThat(app.getAllowPrivilegeEscalation()).isFalse();
            assertThat(app.getRunAsNonRoot()).isTrue();
        }

        @Test
        @DisplayName("the root filesystem is read-only and every capability is dropped")
        void filesystemAndCapabilitiesAreLockedDown() {
            var security = container(build(RunnerFixtures.request())).getSecurityContext();

            assertThat(security.getReadOnlyRootFilesystem()).isTrue();
            assertThat(security.getCapabilities().getDrop()).containsExactly("ALL");
        }

        @Test
        @DisplayName("the runtime-default seccomp profile is applied")
        void seccompIsRuntimeDefault() {
            assertThat(build(RunnerFixtures.request())
                    .getSpec().getTemplate().getSpec().getSecurityContext()
                    .getSeccompProfile().getType())
                    .isEqualTo("RuntimeDefault");
        }

        @Test
        @DisplayName("the runner uses its own service account, not the control plane's")
        void runsAsItsOwnIdentity() {
            assertThat(build(RunnerFixtures.request())
                    .getSpec().getTemplate().getSpec().getServiceAccountName())
                    .isEqualTo(RunnerFixtures.SETTINGS.serviceAccount());
        }
    }

    @Nested
    @DisplayName("what a manifest must never carry")
    class NoSecrets {

        /**
         * Manifests are readable by anyone with cluster access, appear in audit logs and get pasted
         * into tickets. Nothing sensitive may reach one — secrets are mounted at execution time by
         * the runner's own identity, which is why {@code ProvisionRequest} has no field for one.
         */
        @Test
        @DisplayName("no label, annotation, environment value or argument looks like a credential")
        void carriesNothingSensitive() {
            Job job = build(RunnerFixtures.request());
            String rendered = job.toString().toLowerCase(java.util.Locale.ROOT);

            assertThat(rendered)
                    .doesNotContain("password")
                    .doesNotContain("secretref")
                    .doesNotContain("authorization")
                    .doesNotContain("bearer ")
                    .doesNotContain("aws_secret")
                    .doesNotContain("client_secret");

            // The container is told identifiers and keys, and fetches what it needs itself.
            assertThat(container(job).getArgs()).isNullOrEmpty();
        }
    }

    @Nested
    @DisplayName("identity and traceability")
    class Identity {

        /** Labels are how reconciliation finds a run's Jobs again after a control-plane restart. */
        @Test
        @DisplayName("run, shard, team, engine and manager are all labelled")
        void labelsCarryEverythingReconciliationNeeds() {
            ProvisionRequest request = RunnerFixtures.request();
            Map<String, String> labels = build(request).getMetadata().getLabels();

            assertThat(labels)
                    .containsEntry(RunnerJobManifest.LABEL_RUN_ID, request.runId().value().toString())
                    .containsEntry(RunnerJobManifest.LABEL_SHARD_ID,
                            request.shardId().value().toString())
                    .containsEntry(RunnerJobManifest.LABEL_SHARD_INDEX,
                            String.valueOf(request.shardIndex()))
                    .containsEntry(RunnerJobManifest.LABEL_TEAM_ID,
                            request.teamId().value().toString())
                    .containsEntry(RunnerJobManifest.LABEL_ENGINE, request.engine())
                    .containsEntry(RunnerJobManifest.LABEL_MANAGED_BY, RunnerJobManifest.MANAGED_BY);
        }

        /**
         * An annotation rather than a label: {@code sha256:<64 hex>} exceeds the 63-character limit a
         * label value allows, and nothing queries by it — it is evidence of which plan this Job ran.
         */
        @Test
        @DisplayName("the plan digest travels as an annotation, because it cannot fit in a label")
        void planDigestIsAnnotated() {
            ProvisionRequest request = RunnerFixtures.request();
            Job job = build(request);

            assertThat(job.getMetadata().getAnnotations())
                    .containsEntry(RunnerJobManifest.ANNOTATION_PLAN_DIGEST,
                            request.planDigest().value());
            assertThat(request.planDigest().value().length()).isGreaterThan(63);
        }

        @Test
        @DisplayName("a Job name is deterministic, unique per shard and within Kubernetes' limit")
        void nameIsStableAndLegal() {
            ProvisionRequest first = RunnerFixtures.request();
            ProvisionRequest second = RunnerFixtures.requestForShard(1, 2);

            assertThat(RunnerJobManifest.nameFor(first))
                    .isEqualTo(RunnerJobManifest.nameFor(first))
                    .hasSizeLessThanOrEqualTo(63)
                    .matches("[a-z0-9]([-a-z0-9]*[a-z0-9])?");
            assertThat(RunnerJobManifest.nameFor(second))
                    .isNotEqualTo(RunnerJobManifest.nameFor(first));
        }

        /**
         * The runner is told where to find its plan and what to label its output. Objectives are
         * scoped by workload and by scenario, so results labelled any other way could not be judged.
         */
        @Test
        @DisplayName("the runner is told its identity, its plan key and its result labels")
        void environmentCarriesWhatTheRunnerNeeds() {
            ProvisionRequest request = RunnerFixtures.request();

            assertThat(env(build(request)))
                    .containsEntry("PERF_RUN_ID", request.runId().value().toString())
                    .containsEntry("PERF_SHARD_INDEX", String.valueOf(request.shardIndex()))
                    .containsEntry("PERF_SHARD_COUNT", String.valueOf(request.shardCount()))
                    .containsEntry("PERF_WORKLOAD_KEY", request.workloadKey())
                    .containsEntry("PERF_PLAN_DIGEST", request.planDigest().value())
                    .containsEntry("PERF_OPERATION_LABELS", "checkout-load/browse");
        }

        /**
         * The runner publishes what it has measured; nothing calls back the other way.
         *
         * <p>The shard identifier is in the environment because it goes into every progress
         * document, and the control plane refuses a snapshot whose identity does not match the run
         * it is reconciling — the same check that keeps a stale runner's results out of a report.
         */
        @Test
        @DisplayName("the runner is told how often to publish progress, and who it is publishing as")
        void environmentCarriesProgressContract() {
            ProvisionRequest request = RunnerFixtures.request();

            assertThat(env(build(request)))
                    .containsEntry("PERF_PROGRESS_INTERVAL_SECONDS", "5")
                    .containsEntry("PERF_SHARD_ID", request.shardId().value().toString());
        }

        /**
         * A runner is told where its data is, never given the data. A Job manifest is readable by
         * anyone with cluster access, appears in audit logs and gets pasted into tickets — and a
         * dataset is the team's business data.
         */
        @Test
        @DisplayName("dataset partitions travel as fileName=objectKey pairs, never as content")
        void environmentCarriesDatasetKeys() {
            String key = "teams/t/runs/r/shards/s/dataset-client-data.csv";
            ProvisionRequest request = RunnerFixtures.requestWithDatasets(java.util.List.of(
                    new com.perfplatform.domain.execution.DatasetDelivery("client-data.csv", key),
                    new com.perfplatform.domain.execution.DatasetDelivery("skus.csv",
                            "teams/t/runs/r/shards/s/dataset-skus.csv")));

            assertThat(env(build(request)))
                    .containsEntry("PERF_DATASETS", "client-data.csv=" + key
                            + ",skus.csv=teams/t/runs/r/shards/s/dataset-skus.csv");
        }

        @Test
        @DisplayName("a test with no dataset sets the variable empty rather than omitting it")
        void datasetsAreEmptyWhenThereAreNone() {
            assertThat(env(build(RunnerFixtures.request()))).containsEntry("PERF_DATASETS", "");
        }

        @Test
        @DisplayName("session files are named in environment and mounted from a per-shard Secret")
        void sessionFilesUseMountedSecretFiles() {
            ProvisionRequest request = RunnerFixtures.requestWithSecretFiles(java.util.List.of(
                    RunnerFixtures.sessionFile("session_id,session_value\n1,Bearer hidden")));

            Job job = build(request);
            Container container = container(job);

            assertThat(env(job)).containsEntry("PERF_SESSION_FILES", "sessions.csv");
            assertThat(env(job).values()).noneMatch(value -> value != null
                    && value.contains("Bearer hidden"));

            assertThat(job.getSpec().getTemplate().getSpec().getVolumes())
                    .anySatisfy(volume -> {
                        assertThat(volume.getName()).isEqualTo("session-files");
                        assertThat(volume.getSecret().getSecretName())
                                .isEqualTo(RunnerJobManifest.sessionFileSecretNameFor(request));
                        assertThat(volume.getSecret().getItems())
                                .anySatisfy(item -> {
                                    assertThat(item.getKey()).isEqualTo("sessions.csv");
                                    assertThat(item.getPath()).isEqualTo("sessions.csv");
                                });
                    });
            assertThat(container.getVolumeMounts())
                    .anySatisfy(mount -> {
                        assertThat(mount.getName()).isEqualTo("session-files");
                        assertThat(mount.getMountPath()).isEqualTo("/workspace/sessions.csv");
                        assertThat(mount.getSubPath()).isEqualTo("sessions.csv");
                        assertThat(mount.getReadOnly()).isTrue();
                    });
            assertThat(job.toString()).doesNotContain("Bearer hidden");
        }

        /**
         * A runner shares no filesystem with the control plane: it fetches its plan and uploads its
         * results over the network. Without these it holds a key and has nowhere to use it.
         */
        @Test
        @DisplayName("the runner is told which bucket to read its plan from and write results to")
        void environmentCarriesArtifactCoordinates() {
            assertThat(env(build(RunnerFixtures.request())))
                    .containsEntry("PERF_ARTIFACT_BUCKET",
                            RunnerFixtures.SETTINGS.artifacts().bucket())
                    .containsEntry("PERF_ARTIFACT_REGION",
                            RunnerFixtures.SETTINGS.artifacts().region());
        }

        /**
         * The runner is given its prefix rather than left to build one. Key layout is what makes
         * authorization structural, and a runner that constructed its own path could write into
         * another team's prefix.
         */
        @Test
        @DisplayName("the runner is given its own key prefix, not the means to invent one")
        void environmentCarriesTheShardPrefix() {
            ProvisionRequest request = RunnerFixtures.request();

            assertThat(env(build(request)))
                    .containsEntry("PERF_ARTIFACT_PREFIX", "teams/%s/runs/%s/shards/%s/".formatted(
                            request.teamId().value(),
                            request.runId().value(),
                            request.shardId().value()));
        }

        /**
         * Coordinates, never credentials. A runner authenticates with its own projected
         * service-account token; a key passed through a manifest would reach every audit log.
         */
        @Test
        @DisplayName("no storage credential value is passed to the runner")
        void noStorageCredentialIsPassed() {
            assertThat(env(build(RunnerFixtures.request())))
                    .doesNotContainKeys("AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY",
                            "AWS_SESSION_TOKEN", "PERF_ARTIFACT_ACCESS_KEY");
        }

        /**
         * On EKS with real S3 the runner's projected service-account token is enough, and there is
         * no credential to leak. Nothing is mounted in that case.
         */
        @Test
        @DisplayName("no Secret is mounted when the runner's own identity suffices")
        void noSecretWhenPodIdentityIsEnough() {
            assertThat(container(build(RunnerFixtures.request())).getEnvFrom()).isNullOrEmpty();
        }

        /**
         * An S3-compatible store that only understands access keys needs one. It is referenced
         * <b>by name</b>: kubelet injects the values at start, so nothing sensitive passes through
         * the control plane, the manifest, or any log — the same reason a specification carries a
         * {@code secretRef} and never a secret (CLAUDE.md §20).
         */
        @Test
        @DisplayName("a credentials Secret is referenced by name, and its values never appear")
        void credentialsSecretIsReferencedByName() {
            RunnerCellSettings withSecret = RunnerFixtures.settingsWithStorage(
                    new RunnerCellSettings.ArtifactStorage("perf-artifacts", "us-east-1",
                            "http://minio:9000", true, "runner-storage-credentials"));

            Job job = RunnerJobManifest.build(RunnerFixtures.request(), withSecret);
            var envFrom = container(job).getEnvFrom();

            assertThat(envFrom).hasSize(1);
            assertThat(envFrom.get(0).getSecretRef().getName())
                    .isEqualTo("runner-storage-credentials");

            // Not optional: a missing Secret must stop the Pod starting. A runner that started
            // without credentials would fail while uploading, having already generated its load.
            assertThat(envFrom.get(0).getSecretRef().getOptional()).isFalse();

            assertThat(job.toString())
                    .as("the manifest names the Secret and never carries its contents")
                    .doesNotContain("AWS_SECRET_ACCESS_KEY");
        }

        @Test
        @DisplayName("runtime credentials are referenced through a per-shard Secret")
        void runtimeCredentialsUseSecretKeyRefs() {
            ProvisionRequest request = RunnerFixtures.requestWithCredentials(java.util.List.of(
                    new com.perfplatform.domain.execution.RuntimeCredential(
                            "session", "Bearer super-secret-token")));

            Job job = build(request);
            EnvVar credentialEnv = container(job).getEnv().stream()
                    .filter(env -> "PERF_AUTH_SESSION".equals(env.getName()))
                    .findFirst()
                    .orElseThrow();

            assertThat(credentialEnv.getValue()).isNull();
            assertThat(credentialEnv.getValueFrom().getSecretKeyRef().getName())
                    .isEqualTo(RunnerJobManifest.runtimeCredentialSecretNameFor(request));
            assertThat(credentialEnv.getValueFrom().getSecretKeyRef().getKey())
                    .isEqualTo("PERF_AUTH_SESSION");
            assertThat(job.toString()).doesNotContain("super-secret-token");
        }
    }

    @Nested
    @DisplayName("the runner image")
    class Images {

        @Test
        @DisplayName("the image comes from the cell's allowlist, keyed by engine")
        void imageIsSelectedByEngine() {
            assertThat(container(build(RunnerFixtures.request())).getImage())
                    .isEqualTo(RunnerFixtures.SETTINGS.imageFor("jmeter"));
        }

        /**
         * Refused rather than defaulted. A fallback image would mean a misconfiguration produces a
         * run against the wrong engine, whose results would look perfectly valid.
         */
        @Test
        @DisplayName("an engine with no approved image cannot be provisioned at all")
        void unknownEngineIsRefused() {
            assertThatThrownBy(() -> build(RunnerFixtures.requestForEngine("k6")))
                    .isInstanceOf(RunnerCellSettings.UnknownRunnerImageException.class)
                    .hasMessageContaining("k6");
        }

        /**
         * A moving tag means two runs of the same immutable definition version can execute on
         * different runner builds — the plan digest matches while the thing executing it changed.
         */
        @Test
        @DisplayName("a floating image tag is detectable, so startup can refuse it")
        void floatingTagsAreDetected() {
            assertThat(RunnerFixtures.SETTINGS.hasOnlyPinnedImages()).isTrue();

            RunnerCellSettings floating = RunnerFixtures.settingsWithImages(
                    Map.of("jmeter", "registry.internal/jmeter-runner:latest",
                            "playwright", "registry.internal/playwright-runner"));

            assertThat(floating.hasOnlyPinnedImages()).isFalse();
            assertThat(floating.floatingImages()).hasSize(2);
        }

        @Test
        @DisplayName("a digest-pinned image counts as pinned")
        void digestPinnedImageIsAccepted() {
            RunnerCellSettings pinned = RunnerFixtures.settingsWithImages(Map.of("jmeter",
                    "registry.internal/jmeter-runner@sha256:"
                            + "0000000000000000000000000000000000000000000000000000000000000000"));

            assertThat(pinned.hasOnlyPinnedImages()).isTrue();
        }
    }
}
