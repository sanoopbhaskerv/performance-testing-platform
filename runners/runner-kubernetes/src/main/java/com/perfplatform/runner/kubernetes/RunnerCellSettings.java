package com.perfplatform.runner.kubernetes;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * How this deployment is allowed to create runner Jobs.
 *
 * <p>Every value here is an operational decision that differs per cluster, so none of it is
 * hardcoded and none of it comes from a specification. A consumer describes the load they want; the
 * platform decides what a runner is permitted to be. Letting a test choose its own image, service
 * account or resource limits would make every server-side control advisory.
 *
 * @param namespace         where runner Jobs are created. Separate from the control plane's own
 *                          namespace in any real deployment, so a runner cannot see control-plane
 *                          secrets or service accounts
 * @param serviceAccount    the identity a runner Pod runs as. Scoped to uploading its own artifacts
 *                          and nothing else; it must not be the control plane's account
 * @param runnerImages      engine identifier to fully-qualified, version-pinned image. An allowlist,
 *                          not a template: an engine with no entry cannot be provisioned at all
 * @param cpuRequest        CPU request per runner Pod
 * @param cpuLimit          CPU ceiling. A load generator that is throttled reports latencies of
 *                          itself rather than of the system under test, so this is sized generously
 *                          and calibrated — see docs/operations/runner-calibration.md
 * @param memoryRequest     memory request per runner Pod
 * @param memoryLimit       memory ceiling
 * @param workspaceSizeLimit bound on the runner's temporary working volume, so a runaway result file
 *                          fills an {@code emptyDir} rather than the node
 * @param finishedJobTtl    how long a finished Job is kept before Kubernetes reclaims it. Long
 *                          enough that reconciliation can still observe how it ended after a
 *                          control-plane restart; artifacts are already durable by then
 * @param deadlineGrace     added to the plan's own deadline to produce {@code activeDeadlineSeconds},
 *                          covering image pull, startup and artifact upload
 * @param artifacts         where the runner reads its plan from and writes its results to. Without
 *                          this a runner has a key and nowhere to use it
 */
public record RunnerCellSettings(
        String namespace,
        String serviceAccount,
        Map<String, String> runnerImages,
        String cpuRequest,
        String cpuLimit,
        String memoryRequest,
        String memoryLimit,
        String workspaceSizeLimit,
        Duration finishedJobTtl,
        Duration deadlineGrace,
        ArtifactStorage artifacts) {

    /**
     * The object storage a runner uses.
     *
     * <p>Coordinates only. There are deliberately no credential fields: a runner authenticates with
     * its own service identity — a projected service-account token, an instance profile — and a key
     * passed through a Job manifest would be a key in an audit log and, eventually, in a ticket.
     *
     * @param bucket          where artifacts live
     * @param region          storage region, or empty to let the runner's own default apply
     * @param endpoint        set only for an S3-compatible appliance or MinIO; empty means AWS S3
     * @param pathStyleAccess path-style addressing, which most S3-compatible servers require
     * @param credentialsSecret name of a Kubernetes Secret in the runner namespace holding the
     *                        storage credentials, or empty when the runner's own service identity
     *                        suffices. <b>A name, never a value</b> — see the record's javadoc
     */
    public record ArtifactStorage(
            String bucket,
            String region,
            String endpoint,
            boolean pathStyleAccess,
            String credentialsSecret) {

        public ArtifactStorage {
            requireText(bucket, "bucket");
            region = region == null ? "" : region;
            endpoint = endpoint == null ? "" : endpoint;
            credentialsSecret = credentialsSecret == null ? "" : credentialsSecret;
        }

        /** Storage the runner can reach with its own identity — IRSA, or an instance profile. */
        public static ArtifactStorage usingPodIdentity(String bucket, String region,
                String endpoint, boolean pathStyleAccess) {
            return new ArtifactStorage(bucket, region, endpoint, pathStyleAccess, "");
        }

        /**
         * Whether a Secret must be mounted for the runner to authenticate.
         *
         * <p>On EKS with real S3 this is false: a projected service-account token is enough, and no
         * credential exists to leak. It becomes true for an S3-compatible store that only understands
         * access keys — MinIO, most on-premises appliances — where a Secret is the least-bad option
         * and is still a <em>reference</em> the platform holds rather than a value (CLAUDE.md §20).
         */
        public boolean requiresCredentialsSecret() {
            return !credentialsSecret.isBlank();
        }
    }

    public RunnerCellSettings {
        requireText(namespace, "namespace");
        requireText(serviceAccount, "serviceAccount");
        requireText(cpuRequest, "cpuRequest");
        requireText(cpuLimit, "cpuLimit");
        requireText(memoryRequest, "memoryRequest");
        requireText(memoryLimit, "memoryLimit");
        requireText(workspaceSizeLimit, "workspaceSizeLimit");
        Objects.requireNonNull(finishedJobTtl, "finishedJobTtl");
        Objects.requireNonNull(deadlineGrace, "deadlineGrace");
        Objects.requireNonNull(artifacts, "artifacts");
        runnerImages = runnerImages == null ? Map.of() : Map.copyOf(runnerImages);
    }

    /** Raised when an engine has no approved runner image in this cell. */
    public static class UnknownRunnerImageException extends RuntimeException {
        public UnknownRunnerImageException(String engine) {
            super("No approved runner image is configured for engine '" + engine + "'. A runner "
                    + "image is an allowlisted deployment decision, not something a test can choose.");
        }
    }

    /**
     * The image for an engine.
     *
     * <p>Refuses rather than falling back to a default. A default image would mean a
     * misconfiguration produces a run against the wrong engine, whose results would look valid.
     *
     * @throws UnknownRunnerImageException if the engine has no configured image
     */
    public String imageFor(String engine) {
        String image = runnerImages.get(engine);
        if (image == null || image.isBlank()) {
            throw new UnknownRunnerImageException(engine);
        }
        return image;
    }

    /**
     * Whether every configured image is pinned to something other than a floating tag.
     *
     * <p>CLAUDE.md §8 forbids {@code latest}. A moving tag means two runs of the same immutable
     * definition version can execute on different runner builds, which quietly breaks the promise
     * that a run is reproducible — the plan digest would match while the thing executing it changed.
     */
    public boolean hasOnlyPinnedImages() {
        return runnerImages.values().stream().noneMatch(RunnerCellSettings::isFloating);
    }

    /** Configured images using a floating tag, for a startup refusal that names them. */
    public java.util.List<String> floatingImages() {
        return runnerImages.values().stream().filter(RunnerCellSettings::isFloating).sorted().toList();
    }

    private static boolean isFloating(String image) {
        if (image.contains("@sha256:")) {
            return false;
        }
        int tagSeparator = image.lastIndexOf(':');
        // No tag at all resolves to :latest, which is the same problem written differently.
        if (tagSeparator < 0 || image.indexOf('/', tagSeparator) > 0) {
            return true;
        }
        String tag = image.substring(tagSeparator + 1);
        return tag.isBlank() || "latest".equalsIgnoreCase(tag) || "main".equalsIgnoreCase(tag)
                || "master".equalsIgnoreCase(tag) || "edge".equalsIgnoreCase(tag);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be null or blank");
        }
    }
}
