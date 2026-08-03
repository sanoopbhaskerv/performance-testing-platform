package com.perfplatform.domain.execution;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

/**
 * One secret file a runner needs in its workspace before it starts.
 *
 * <p>The content is runtime credential material. It is not a durable artifact, not a dataset, and
 * not report evidence. Providers must deliver it through an execution-environment secret mechanism
 * such as a Kubernetes Secret volume, then remove that secret when the run reaches a terminal
 * outcome. The timestamps make the cleanup contract explicit even if the control plane dies before
 * observing the runner finish.
 */
public final class SecretFileDelivery {

    private final String fileName;
    private final byte[] content;
    private final Instant expiresAt;
    private final Instant deleteAfter;

    public SecretFileDelivery(String fileName, byte[] content, Instant expiresAt,
            Instant deleteAfter) {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("fileName must not be null or blank");
        }
        if (fileName.contains("/") || fileName.contains("\\") || fileName.contains("..")) {
            throw new IllegalArgumentException(
                    "fileName must be a plain file name, not a path: '" + fileName + "'");
        }
        Objects.requireNonNull(content, "content");
        if (content.length == 0) {
            throw new IllegalArgumentException("secret file content must not be empty");
        }
        this.fileName = fileName;
        this.content = Arrays.copyOf(content, content.length);
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        this.deleteAfter = Objects.requireNonNull(deleteAfter, "deleteAfter");
        if (deleteAfter.isAfter(expiresAt)) {
            throw new IllegalArgumentException("deleteAfter must not be after expiresAt");
        }
    }

    public String fileName() {
        return fileName;
    }

    public byte[] content() {
        return Arrays.copyOf(content, content.length);
    }

    public String utf8Content() {
        return new String(content, StandardCharsets.UTF_8);
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public Instant deleteAfter() {
        return deleteAfter;
    }

    @Override
    public String toString() {
        return "SecretFileDelivery[fileName=" + fileName + ", content=<redacted>, bytes="
                + content.length + ", expiresAt=" + expiresAt + ", deleteAfter=" + deleteAfter
                + "]";
    }
}
