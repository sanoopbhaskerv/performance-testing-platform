package com.perfplatform.domain.execution;

/**
 * One dataset partition a shard must have in its workspace before it starts.
 *
 * <p>A key and a file name — never content and never a path. The control plane has already sliced
 * the dataset so this shard's rows overlap with no other shard's, stored the slice as a run
 * artifact, and named the object. All the provider does is put those bytes where the engine will
 * look for them.
 *
 * <h2>Why the control plane slices rather than the runner</h2>
 * A runner taking every <i>n</i>th row itself would be untestable without rebuilding an image, and
 * what each generator sent would exist only inside a pod that no longer exists. Slicing here makes
 * every partition a stored, digested artifact — so a run's inputs are as recoverable as its outputs,
 * and "which rows did shard 3 send?" has an answer. The cost is that the control plane reads the
 * whole file, which is why upload size is capped.
 *
 * @param fileName  the plain name the engine expects in its working directory, e.g.
 *                  {@code orders.csv}. Plain by construction: a name with a separator could escape
 *                  the workspace
 * @param objectKey the stored partition's key, which the runner fetches with its own identity
 */
public record DatasetDelivery(String fileName, String objectKey) {

    public DatasetDelivery {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("fileName must not be null or blank");
        }
        if (fileName.contains("/") || fileName.contains("\\") || fileName.contains("..")) {
            throw new IllegalArgumentException(
                    "fileName must be a plain file name, not a path: '" + fileName + "'");
        }
        if (objectKey == null || objectKey.isBlank()) {
            throw new IllegalArgumentException("objectKey must not be null or blank");
        }
    }
}
