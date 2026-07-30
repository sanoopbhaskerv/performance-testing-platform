package com.perfplatform.runner.kubernetes;

import com.perfplatform.domain.execution.ExecutionPhase;
import com.perfplatform.domain.execution.ExecutionProvider;
import com.perfplatform.domain.execution.ProvisionRequest;
import com.perfplatform.domain.run.RunnerShard.ExecutionHandle;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobCondition;
import io.fabric8.kubernetes.api.model.batch.v1.JobStatus;
import io.fabric8.kubernetes.api.model.DeletionPropagation;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Runs a workload as an isolated, ephemeral Kubernetes Job.
 *
 * <p>The production execution provider. CLAUDE.md §4 forbids the control plane from generating
 * material load, and this is what satisfies it: the control plane decides, a Job executes, and the
 * two share nothing but an artifact store and a handle.
 *
 * <p>Engine-neutral. It starts a container, watches it and stops it; which container, and what that
 * container does, is a matter of configuration. Adding Playwright is adding an image to the cell's
 * allowlist, not changing this class.
 *
 * <h2>Never replays a material run</h2>
 * Two mechanisms, because one is not enough. {@code backoffLimit: 0} stops Kubernetes retrying a
 * failed Pod, and {@link #provision} refuses to recreate a Job that already exists rather than
 * replacing it. A retried performance test is a second load test against a real system and two
 * overlapping sets of results, and it would look like an ordinary run.
 *
 * <h2>What it never does</h2>
 * It does not authorize anyone, choose a team, override a quota or touch run state. It reports; the
 * control plane decides. It has no access to persistence to do otherwise.
 */
public final class KubernetesExecutionProvider implements ExecutionProvider {

    private static final Logger log = LoggerFactory.getLogger(KubernetesExecutionProvider.class);

    public static final String PROVIDER_ID = "kubernetes";

    /**
     * Deletes the Pods a Job created, not just the Job record.
     *
     * <p>Without this, deleting a Job orphans its Pods and the load keeps flowing while the platform
     * reports the run cancelled — the single worst outcome cancellation can have.
     */
    private static final String CASCADE_FOREGROUND = "Foreground";

    private final KubernetesClient client;
    private final RunnerCellSettings settings;

    public KubernetesExecutionProvider(KubernetesClient client, RunnerCellSettings settings) {
        this.client = Objects.requireNonNull(client, "client");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    /**
     * Yes. This is the provider the platform exists to have.
     *
     * <p>A runner Job sends real traffic to the resolved target, so its measurements describe a real
     * interaction and its objectives can genuinely pass or fail.
     */
    @Override
    public boolean generatesRealLoad() {
        return true;
    }

    /**
     * Creates the Job for one shard.
     *
     * <p>An existing Job with the same name is <em>not</em> replaced. It means an earlier pass
     * created it and died before recording the handle; the workload may well be running right now,
     * and recreating it would double the load. The existing Job is adopted instead, which is also
     * what makes provisioning idempotent across a control-plane restart.
     *
     * <p>Ordinary refusals — quota, admission webhook, an unschedulable Pod — are outcomes, not
     * exceptions: the run records them with a reason a consumer can act on.
     */
    @Override
    public ProvisioningResult provision(ProvisionRequest request) {
        String name = RunnerJobManifest.nameFor(request);

        try {
            Job existing = client.batch().v1().jobs()
                    .inNamespace(settings.namespace()).withName(name).get();

            if (existing != null) {
                log.warn("Adopting an existing runner Job rather than recreating it "
                                + "runId={} shardIndex={} job={} uid={}",
                        request.runId(), request.shardIndex(), name, uidOf(existing));
                return ProvisioningResult.accepted(handleFor(existing, name));
            }

            Job created = client.batch().v1().jobs()
                    .inNamespace(settings.namespace())
                    .resource(RunnerJobManifest.build(request, settings))
                    .create();

            log.info("Runner Job created runId={} teamId={} shardIndex={} engine={} job={} uid={} "
                            + "namespace={}",
                    request.runId(), request.teamId(), request.shardIndex(), request.engine(),
                    name, uidOf(created), settings.namespace());

            return ProvisioningResult.accepted(handleFor(created, name));

        } catch (RunnerCellSettings.UnknownRunnerImageException e) {
            // A configuration problem, and one a consumer can be told about plainly: their engine is
            // not deployable in this cell. Not an exception, because retrying will not fix it.
            log.error("Refusing to provision runId={} engine={}: {}",
                    request.runId(), request.engine(), e.getMessage());
            return ProvisioningResult.rejected(e.getMessage());

        } catch (KubernetesClientException e) {
            log.error("Runner Job creation refused runId={} shardIndex={} job={} status={}",
                    request.runId(), request.shardIndex(), name, e.getCode(), e);
            // Sanitized: the cluster's own message can carry namespace policy, webhook and node
            // detail that has no business in a consumer's CI log.
            return ProvisioningResult.rejected(
                    "The cluster refused to create the load generator (status " + e.getCode() + ").");
        }
    }

    /**
     * Reports what the cluster currently says about a Job.
     *
     * <p>Never guesses a terminal phase. A wrong "finished" frees a capacity slot while the load
     * continues, so an unreachable API server produces {@link ExecutionPhase#UNKNOWN} and the run's
     * heartbeat tolerance decides what to do about it.
     */
    @Override
    public ExecutionStatus inspect(ExecutionHandle handle) {
        Job job;
        try {
            job = client.batch().v1().jobs()
                    .inNamespace(settings.namespace()).withName(handle.name()).get();
        } catch (KubernetesClientException e) {
            log.warn("Could not inspect runner Job job={} status={}", handle.name(), e.getCode());
            return ExecutionStatus.of(ExecutionPhase.UNKNOWN);
        }

        if (job == null) {
            // Either the TTL reclaimed a finished Job or it never existed. Both mean nothing is
            // executing now, which is the part that matters for capacity.
            return ExecutionStatus.of(ExecutionPhase.NOT_FOUND);
        }

        // A Job that was recreated under the same name is not this run's Job. Comparing UIDs is what
        // stops one run's reconciliation from reading another's outcome.
        if (!handle.uid().equals(uidOf(job))) {
            log.warn("Runner Job job={} has uid={} but the run recorded uid={}; treating as gone",
                    handle.name(), uidOf(job), handle.uid());
            return ExecutionStatus.of(ExecutionPhase.NOT_FOUND);
        }

        return statusOf(job);
    }

    /**
     * Deletes the Job and the Pods it created.
     *
     * <p>Foreground cascade, so Pods go with it — an orphaned Pod would keep generating load while
     * the platform reported the run stopped.
     *
     * <p>Reports {@code terminated} only once the Job is actually gone. Deletion is asynchronous, so
     * the usual answer on the first call is "requested, not yet observed", and the reconciler asks
     * again rather than declaring traffic ceased before it had.
     */
    @Override
    public CancellationResult cancel(ExecutionHandle handle) {
        try {
            var jobs = client.batch().v1().jobs()
                    .inNamespace(settings.namespace()).withName(handle.name());

            if (jobs.get() == null) {
                // Already gone. Cancellation is retried by design and a second attempt must not
                // fail, or a run could never reach a terminal state.
                return CancellationResult.terminated("No such runner Job; already terminated");
            }

            jobs.withPropagationPolicy(DeletionPropagation.FOREGROUND).delete();

            boolean gone = jobs.get() == null;
            log.info("Runner Job deletion requested job={} cascade={} observedGone={}",
                    handle.name(), CASCADE_FOREGROUND, gone);

            return gone
                    ? CancellationResult.terminated("Runner Job deleted")
                    : CancellationResult.pending("Deletion requested; awaiting termination");

        } catch (KubernetesClientException e) {
            log.error("Could not delete runner Job job={} status={}", handle.name(), e.getCode(), e);
            return CancellationResult.pending(
                    "The cluster did not accept the stop request (status " + e.getCode() + ").");
        }
    }

    /**
     * Translates a Job's status into a phase.
     *
     * <p>Conditions first, because they are what Kubernetes uses to say a Job is definitively over —
     * including {@code DeadlineExceeded}, which is a failure the platform must see rather than a Job
     * that merely stopped having active Pods.
     */
    private static ExecutionStatus statusOf(Job job) {
        JobStatus status = job.getStatus();
        if (status == null) {
            return ExecutionStatus.of(ExecutionPhase.PENDING);
        }

        Instant startedAt = parse(status.getStartTime());
        Instant completedAt = parse(status.getCompletionTime());

        Optional<JobCondition> complete = condition(status, "Complete");
        if (complete.isPresent()) {
            return ExecutionStatus.finished(ExecutionPhase.SUCCEEDED, 0,
                    startedAt, completedAt != null ? completedAt : parse(complete.get().getLastTransitionTime()));
        }

        Optional<JobCondition> failed = condition(status, "Failed");
        if (failed.isPresent()) {
            // Exit code 1 stands for "the Job failed"; Kubernetes reports a reason, not a code, and
            // inventing a specific one would be a fact the cluster never stated.
            return ExecutionStatus.finished(ExecutionPhase.FAILED, 1,
                    startedAt, parse(failed.get().getLastTransitionTime()));
        }

        // Not yet terminal. Active Pods mean running; none yet means it is still being scheduled.
        int active = status.getActive() == null ? 0 : status.getActive();
        if (active > 0) {
            return new ExecutionStatus(ExecutionPhase.RUNNING, Optional.empty(),
                    Optional.ofNullable(startedAt), Optional.empty());
        }
        return ExecutionStatus.of(ExecutionPhase.PENDING);
    }

    private static Optional<JobCondition> condition(JobStatus status, String type) {
        List<JobCondition> conditions = status.getConditions();
        if (conditions == null) {
            return Optional.empty();
        }
        return conditions.stream()
                .filter(condition -> type.equals(condition.getType()))
                .filter(condition -> "True".equalsIgnoreCase(condition.getStatus()))
                .findFirst();
    }

    private static ExecutionHandle handleFor(Job job, String name) {
        // The UID, not the name. Names are reusable; a UID identifies this exact Job, which is what
        // stops a recreated Job from being mistaken for the original.
        return new ExecutionHandle(PROVIDER_ID, name, uidOf(job));
    }

    private static String uidOf(Job job) {
        return job.getMetadata() == null || job.getMetadata().getUid() == null
                ? "unknown"
                : job.getMetadata().getUid();
    }

    private static Instant parse(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(timestamp);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
