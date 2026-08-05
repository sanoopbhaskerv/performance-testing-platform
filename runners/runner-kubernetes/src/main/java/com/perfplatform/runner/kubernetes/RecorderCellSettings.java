package com.perfplatform.runner.kubernetes;

import java.time.Duration;
import java.util.Objects;

/**
 * How this deployment is allowed to create recorder browser Pods.
 *
 * <p>A recorder Pod is not an ordinary load runner: it carries a user-driven browser inside the
 * runner network. The cell settings are therefore separate, so operators can size and lock down the
 * recorder differently from JMeter or Playwright workload Jobs.
 *
 * @param namespace where recorder Jobs are created
 * @param serviceAccount the tokenless identity the recorder Pod runs as
 * @param image approved, pinned recorder image
 * @param cpuRequest CPU request per recorder Pod
 * @param cpuLimit CPU limit per recorder Pod
 * @param memoryRequest memory request per recorder Pod
 * @param memoryLimit memory limit per recorder Pod
 * @param workspaceSizeLimit size bound for temporary browser workspace
 * @param finishedJobTtl how long finished recorder Jobs remain for reconciliation/diagnosis
 * @param deadlineGrace added to the requested deadline for image pull and shutdown
 * @param idleTimeout maximum idle time before the recorder should close itself
 * @param networkEnforcementEnabled whether Kubernetes NetworkPolicy is emitted for each session
 */
public record RecorderCellSettings(
        String namespace,
        String serviceAccount,
        String image,
        String cpuRequest,
        String cpuLimit,
        String memoryRequest,
        String memoryLimit,
        String workspaceSizeLimit,
        Duration finishedJobTtl,
        Duration deadlineGrace,
        Duration idleTimeout,
        boolean networkEnforcementEnabled) {

    public RecorderCellSettings {
        requireText(namespace, "namespace");
        requireText(serviceAccount, "serviceAccount");
        requireText(image, "image");
        requireText(cpuRequest, "cpuRequest");
        requireText(cpuLimit, "cpuLimit");
        requireText(memoryRequest, "memoryRequest");
        requireText(memoryLimit, "memoryLimit");
        requireText(workspaceSizeLimit, "workspaceSizeLimit");
        Objects.requireNonNull(finishedJobTtl, "finishedJobTtl");
        Objects.requireNonNull(deadlineGrace, "deadlineGrace");
        Objects.requireNonNull(idleTimeout, "idleTimeout");
    }

    public boolean hasPinnedImage() {
        return !isFloating(image);
    }

    private static boolean isFloating(String image) {
        if (image.contains("@sha256:")) {
            return false;
        }
        int tagSeparator = image.lastIndexOf(':');
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
