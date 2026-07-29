package com.perfplatform.engine.spi;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * The file a runner actually executes, for one shard.
 *
 * <p>Bytes rather than a path. A path would be a promise about a filesystem the control plane and
 * the runner do not share — the same mistake V1 made with report paths, where a durable reference
 * turned out to name a directory inside a pod that no longer exists. The caller decides where this
 * goes, and it goes through the artifact store.
 *
 * @param fileName    suggested name, used as the artifact's key suffix
 * @param contentType MIME type, recorded on the stored artifact
 * @param content     the rendered file
 */
public record RenderedWorkload(String fileName, String contentType, String content) {

    public RenderedWorkload {
        Objects.requireNonNull(content, "content");
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("fileName must not be null or blank");
        }
        contentType = contentType == null || contentType.isBlank()
                ? "application/octet-stream" : contentType;
    }

    public byte[] bytes() {
        return content.getBytes(StandardCharsets.UTF_8);
    }

    public int sizeBytes() {
        return bytes().length;
    }
}
