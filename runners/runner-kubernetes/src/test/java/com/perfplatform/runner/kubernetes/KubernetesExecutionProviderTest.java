package com.perfplatform.runner.kubernetes;

import com.perfplatform.domain.execution.ExecutionPhase;
import com.perfplatform.domain.execution.ExecutionProvider.CancellationResult;
import com.perfplatform.domain.execution.ExecutionProvider.ExecutionStatus;
import com.perfplatform.domain.execution.ExecutionProvider.ProvisioningResult;
import com.perfplatform.domain.execution.ProvisionRequest;
import com.perfplatform.domain.run.RunnerShard.ExecutionHandle;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The provider against an in-process API server.
 *
 * <p>Covers what a manifest test cannot: what happens when a Job already exists, when one is gone,
 * when the same name has been reused, and when the cluster refuses. Each is a situation
 * reconciliation genuinely reaches, and getting any of them wrong either duplicates load or frees a
 * capacity slot while traffic is still flowing.
 *
 * <p>A mock server rather than a real cluster, deliberately. CLAUDE.md §25 asks for the Kubernetes
 * adapter to be tested this way before requiring a cluster, so these guarantees hold in ordinary CI.
 *
 * <p>Deliberately flat rather than grouped into {@code @Nested} classes: the fabric8 extension
 * injects its client into the annotated class only, and a nested class receives null.
 */
@EnableKubernetesMockClient(crud = true)
class KubernetesExecutionProviderTest {

    KubernetesClient client;

    private KubernetesExecutionProvider provider() {
        return new KubernetesExecutionProvider(client, RunnerFixtures.SETTINGS);
    }

    private Job jobNamed(String name) {
        return client.batch().v1().jobs()
                .inNamespace(RunnerFixtures.SETTINGS.namespace()).withName(name).get();
    }

    private Secret secretNamed(String name) {
        return client.secrets()
                .inNamespace(RunnerFixtures.SETTINGS.namespace()).withName(name).get();
    }

    private ExecutionHandle provisioned() {
        return provider().provision(RunnerFixtures.request()).handle().orElseThrow();
    }

    private void replaceStatus(Job replacement) {
        client.batch().v1().jobs()
                .inNamespace(RunnerFixtures.SETTINGS.namespace())
                .resource(replacement)
                .updateStatus();
    }

    // ── Provisioning ─────────────────────────────────────────────────────

    @Test
    @DisplayName("a shard becomes a Job in the runner namespace, and the handle carries its UID")
    void createsJob() {
        ProvisionRequest request = RunnerFixtures.request();

        ProvisioningResult result = provider().provision(request);

        assertThat(result.isAccepted()).isTrue();
        ExecutionHandle handle = result.handle().orElseThrow();
        assertThat(handle.providerId()).isEqualTo(KubernetesExecutionProvider.PROVIDER_ID);
        assertThat(handle.name()).isEqualTo(RunnerJobManifest.nameFor(request));

        Job created = jobNamed(handle.name());
        assertThat(created).isNotNull();
        assertThat(handle.uid()).isEqualTo(created.getMetadata().getUid());
    }

    /**
     * The situation an earlier pass leaves when it dies between creating the Job and recording the
     * handle. The Job may well be generating load right now; recreating it would double that load,
     * so it is adopted instead. This is also what makes provisioning idempotent across a restart.
     */
    @Test
    @DisplayName("an existing Job is adopted, never recreated")
    void adoptsExistingJobRatherThanDuplicatingLoad() {
        ProvisionRequest request = RunnerFixtures.request();

        ProvisioningResult first = provider().provision(request);
        ProvisioningResult second = provider().provision(request);

        assertThat(second.isAccepted()).isTrue();
        assertThat(second.handle().orElseThrow().uid())
                .isEqualTo(first.handle().orElseThrow().uid());

        assertThat(client.batch().v1().jobs()
                .inNamespace(RunnerFixtures.SETTINGS.namespace()).list().getItems())
                .as("a second provision must not create a second load generator")
                .hasSize(1);
    }

    /**
     * An outcome, not an exception. A run must be able to record it with a reason a consumer can act
     * on, rather than surfacing as an internal error.
     */
    @Test
    @DisplayName("an engine with no approved image is rejected with a usable reason")
    void unknownEngineIsRejectedNotThrown() {
        ProvisioningResult result = provider().provision(RunnerFixtures.requestForEngine("k6"));

        assertThat(result.isAccepted()).isFalse();
        assertThat(result.failureReason().orElseThrow())
                .contains("k6")
                .contains("runner image");
    }

    @Test
    @DisplayName("session files become a mounted Secret with explicit cleanup timestamps")
    void createsSessionFileSecret() {
        ProvisionRequest request = RunnerFixtures.requestWithSecretFiles(List.of(
                RunnerFixtures.sessionFile("session_id,session_value\n1,Bearer hidden")));

        ProvisioningResult result = provider().provision(request);

        assertThat(result.isAccepted()).isTrue();
        Secret secret = secretNamed(RunnerJobManifest.sessionFileSecretNameFor(request));
        assertThat(secret).isNotNull();
        assertThat(secret.getStringData()).containsEntry(
                "sessions.csv", "session_id,session_value\n1,Bearer hidden");
        assertThat(secret.getMetadata().getAnnotations())
                .containsKey("perfplatform.ameriprise.com/secret-expires-at")
                .containsKey("perfplatform.ameriprise.com/secret-delete-after");
    }

    @Test
    @DisplayName("runtime credential Secrets also carry explicit cleanup timestamps")
    void createsRuntimeCredentialSecretWithCleanupTimestamps() {
        ProvisionRequest request = RunnerFixtures.requestWithCredentials(List.of(
                new com.perfplatform.domain.execution.RuntimeCredential("test-user", "Bearer hidden")));

        ProvisioningResult result = provider().provision(request);

        assertThat(result.isAccepted()).isTrue();
        Secret secret = secretNamed(RunnerJobManifest.runtimeCredentialSecretNameFor(request));
        assertThat(secret).isNotNull();
        assertThat(secret.getStringData()).containsEntry("PERF_AUTH_TEST_USER", "Bearer hidden");
        assertThat(secret.getMetadata().getAnnotations())
                .containsKey("perfplatform.ameriprise.com/secret-expires-at")
                .containsKey("perfplatform.ameriprise.com/secret-delete-after");
    }

    // ── Observing ────────────────────────────────────────────────────────

    @Test
    @DisplayName("a Job with no active Pods yet is pending, not running")
    void scheduledJobIsPending() {
        assertThat(provider().inspect(provisioned()).phase()).isEqualTo(ExecutionPhase.PENDING);
    }

    @Test
    @DisplayName("a Job with an active Pod is running")
    void activeJobIsRunning() {
        ExecutionHandle handle = provisioned();
        Job job = jobNamed(handle.name());

        replaceStatus(new JobBuilder(job)
                .withNewStatus()
                    .withActive(1)
                    .withStartTime("2026-07-30T10:00:00Z")
                .endStatus()
                .build());

        ExecutionStatus status = provider().inspect(handle);
        assertThat(status.phase()).isEqualTo(ExecutionPhase.RUNNING);
        assertThat(status.startedAt()).isPresent();
    }

    @Test
    @DisplayName("a Complete condition finishes the shard successfully")
    void completedJobSucceeds() {
        ExecutionHandle handle = provisioned();
        Job job = jobNamed(handle.name());

        replaceStatus(new JobBuilder(job)
                .withNewStatus()
                    .withStartTime("2026-07-30T10:00:00Z")
                    .withCompletionTime("2026-07-30T10:20:00Z")
                    .addNewCondition()
                        .withType("Complete").withStatus("True")
                        .withLastTransitionTime("2026-07-30T10:20:00Z")
                    .endCondition()
                .endStatus()
                .build());

        ExecutionStatus status = provider().inspect(handle);
        assertThat(status.phase()).isEqualTo(ExecutionPhase.SUCCEEDED);
        assertThat(status.exitCode()).contains(0);
        assertThat(status.completedAt()).isPresent();
    }

    @Test
    @DisplayName("terminal observation deletes session file Secrets")
    void completedJobDeletesSessionSecret() {
        ProvisionRequest request = RunnerFixtures.requestWithSecretFiles(List.of(
                RunnerFixtures.sessionFile("session_id,session_value\n1,Bearer hidden")));
        ExecutionHandle handle = provider().provision(request).handle().orElseThrow();
        assertThat(secretNamed(RunnerJobManifest.sessionFileSecretNameFor(request))).isNotNull();
        Job job = jobNamed(handle.name());

        replaceStatus(new JobBuilder(job)
                .withNewStatus()
                    .addNewCondition()
                        .withType("Complete").withStatus("True")
                        .withLastTransitionTime("2026-07-30T10:20:00Z")
                    .endCondition()
                .endStatus()
                .build());

        assertThat(provider().inspect(handle).phase()).isEqualTo(ExecutionPhase.SUCCEEDED);
        assertThat(secretNamed(RunnerJobManifest.sessionFileSecretNameFor(request))).isNull();
    }

    /**
     * Includes {@code DeadlineExceeded}, which is how the cluster reports that it stopped a workload
     * that overran. The platform must see that as a failure rather than as a Job that merely stopped
     * having active Pods.
     */
    @Test
    @DisplayName("a Failed condition finishes the shard as failed")
    void failedJobFails() {
        ExecutionHandle handle = provisioned();
        Job job = jobNamed(handle.name());

        replaceStatus(new JobBuilder(job)
                .withNewStatus()
                    .addNewCondition()
                        .withType("Failed").withStatus("True").withReason("DeadlineExceeded")
                        .withLastTransitionTime("2026-07-30T10:25:00Z")
                    .endCondition()
                .endStatus()
                .build());

        assertThat(provider().inspect(handle).phase()).isEqualTo(ExecutionPhase.FAILED);
    }

    @Test
    @DisplayName("a Job the TTL has reclaimed reports as gone, not as still running")
    void missingJobIsNotFound() {
        ProvisionRequest request = RunnerFixtures.requestWithSecretFiles(List.of(
                RunnerFixtures.sessionFile("session_id,session_value\n1,Bearer hidden")));
        ExecutionHandle handle = provider().provision(request).handle().orElseThrow();
        assertThat(secretNamed(RunnerJobManifest.sessionFileSecretNameFor(request))).isNotNull();
        client.batch().v1().jobs()
                .inNamespace(RunnerFixtures.SETTINGS.namespace())
                .withName(handle.name()).delete();

        assertThat(provider().inspect(handle).phase()).isEqualTo(ExecutionPhase.NOT_FOUND);
        assertThat(secretNamed(RunnerJobManifest.sessionFileSecretNameFor(request))).isNull();
    }

    /**
     * Names are reusable; a UID identifies one exact Job. Without this check, a Job recreated under
     * the same name would have its outcome read as though it belonged to the earlier run.
     */
    @Test
    @DisplayName("a different Job reusing the same name is not mistaken for this one")
    void uidMismatchIsNotThisRunsJob() {
        ProvisionRequest request = RunnerFixtures.requestWithSecretFiles(List.of(
                RunnerFixtures.sessionFile("session_id,session_value\n1,Bearer hidden")));
        ExecutionHandle handle = provider().provision(request).handle().orElseThrow();
        assertThat(secretNamed(RunnerJobManifest.sessionFileSecretNameFor(request))).isNotNull();

        ExecutionHandle stale =
                new ExecutionHandle(handle.providerId(), handle.name(), "a-different-uid");

        assertThat(provider().inspect(stale).phase()).isEqualTo(ExecutionPhase.NOT_FOUND);
        assertThat(secretNamed(RunnerJobManifest.sessionFileSecretNameFor(request))).isNull();
    }

    // ── Cancelling ───────────────────────────────────────────────────────

    @Test
    @DisplayName("cancelling deletes the Job")
    void cancellingDeletesTheJob() {
        ProvisionRequest request = RunnerFixtures.requestWithSecretFiles(List.of(
                RunnerFixtures.sessionFile("session_id,session_value\n1,Bearer hidden")));
        ExecutionHandle handle = provider().provision(request).handle().orElseThrow();
        assertThat(secretNamed(RunnerJobManifest.sessionFileSecretNameFor(request))).isNotNull();

        CancellationResult result = provider().cancel(handle);

        assertThat(result.terminated()).isTrue();
        assertThat(jobNamed(handle.name())).isNull();
        assertThat(secretNamed(RunnerJobManifest.sessionFileSecretNameFor(request))).isNull();
    }

    /**
     * Cancellation is retried by design — a person who presses stop twice must not break the run. A
     * second attempt that failed would leave a run unable to reach a terminal state.
     */
    @Test
    @DisplayName("cancelling something already gone succeeds")
    void cancellingTwiceSucceeds() {
        ExecutionHandle handle = provisioned();

        provider().cancel(handle);
        CancellationResult second = provider().cancel(handle);

        assertThat(second.terminated()).isTrue();
        assertThat(second.detail().orElseThrow()).contains("already terminated");
    }

    // ── Identity ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("this provider declares that it generates real load")
    void declaresRealLoad() {
        assertThat(provider().generatesRealLoad()).isTrue();
        assertThat(provider().providerId()).isEqualTo("kubernetes");
    }

    /**
     * Reconciliation finds a run's Jobs by label after a control-plane restart, so the selector and
     * the labels the manifest writes have to agree. They are defined in one place for that reason.
     */
    @Test
    @DisplayName("the run selector matches the labels the manifest writes")
    void selectorMatchesLabels() {
        ProvisionRequest request = RunnerFixtures.request();
        provider().provision(request);

        List<Job> found = client.batch().v1().jobs()
                .inNamespace(RunnerFixtures.SETTINGS.namespace())
                .withLabels(RunnerJobManifest.selectorForRun(request.runId().value().toString()))
                .list().getItems();

        assertThat(found).hasSize(1);
    }
}
