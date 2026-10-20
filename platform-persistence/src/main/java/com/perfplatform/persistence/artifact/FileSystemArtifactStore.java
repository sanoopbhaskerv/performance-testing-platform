package com.perfplatform.persistence.artifact;

import com.perfplatform.domain.identity.ArtifactId;
import com.perfplatform.domain.identity.RunnerShardId;
import com.perfplatform.domain.identity.TestRunId;
import com.perfplatform.domain.port.ArtifactStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * An artifact store backed by a local directory.
 *
 * <p>For development and for the local execution provider. The production implementation is
 * S3-compatible object storage and arrives with the Kubernetes provider in Increment 1E — this one
 * cannot serve a multi-replica control plane, because a file written by one replica is invisible to
 * the others.
 *
 * <p>It is nonetheless a real implementation of the port, not a stub: keys are the same shape as
 * production's, content is digested and verified, and nothing outside this class ever sees a
 * filesystem path. That is what makes the swap in 1E a configuration change rather than a
 * refactoring.
 *
 * <h2>Writes are atomic</h2>
 * Content is written to a temporary file and moved into place. A reader that opened a half-written
 * results file would parse a truncated document and report a shorter test than the one that ran.
 */
public class FileSystemArtifactStore implements ArtifactStore {

    private static final Logger log = LoggerFactory.getLogger(FileSystemArtifactStore.class);

    private final Path root;
    private final Clock clock;

    /** Where artifacts and their metadata live, mirroring the production key layout. */
    private final List<StoredArtifact> index = new ArrayList<>();

    public FileSystemArtifactStore(Path root, Clock clock) {
        this.root = root.toAbsolutePath().normalize();
        this.clock = clock;
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new ArtifactStoreException("Could not create the artifact root " + this.root, e);
        }
        log.info("Artifacts are stored on the local filesystem at {}. Not durable across replicas "
                + "and unreachable from a runner Pod; set platform.orchestration.artifacts.store=s3 "
                + "for anything beyond local development.", this.root);
    }

    private record StoredArtifact(ArtifactReference reference, Path file) {
    }

    @Override
    public synchronized ArtifactReference store(ArtifactDescriptor descriptor, byte[] content) {
        byte[] body = content == null ? new byte[0] : content;
        String objectKey = keyFor(descriptor);
        Path file = resolveWithinRoot(objectKey);

        try {
            Files.createDirectories(file.getParent());

            // Written beside the target and moved, so a reader never sees a partial file.
            Path temporary = Files.createTempFile(file.getParent(), ".partial-", ".tmp");
            Files.write(temporary, body);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new ArtifactStoreException("Could not store artifact " + objectKey, e);
        }

        ArtifactReference reference = new ArtifactReference(
                ArtifactId.generate(),
                descriptor.runId(),
                descriptor.shardId(),
                descriptor.type(),
                objectKey,
                digestOf(body),
                descriptor.contentType(),
                body.length,
                clock.instant());

        // Re-storing the same key replaces its entry rather than appending a second one, so a
        // repeated reconciliation pass does not double-count a run's artifacts.
        index.removeIf(stored -> stored.reference().objectKey().equals(objectKey));
        index.add(new StoredArtifact(reference, file));

        return reference;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Resolves the key against the root rather than consulting the in-memory index. The index only
     * knows what this process wrote, so an index lookup would make every artifact unreadable after a
     * restart — including the ones a report links to, which are exactly the artifacts someone opens
     * days later. The durable reference lives in the database; the key is all that is needed to find
     * the content.
     */
    @Override
    public synchronized Optional<byte[]> read(ArtifactReference reference) {
        return readFile(resolveWithinRoot(reference.objectKey()));
    }

    @Override
    public synchronized void delete(ArtifactReference reference) {
        Path file = resolveWithinRoot(reference.objectKey());
        try {
            Files.deleteIfExists(file);
            index.removeIf(stored -> stored.reference().objectKey().equals(reference.objectKey()));
        } catch (IOException e) {
            throw new ArtifactStoreException(
                    "Could not delete artifact " + reference.objectKey(), e);
        }
    }

    @Override
    public synchronized List<ArtifactReference> listForRun(TestRunId runId) {
        return index.stream()
                .map(StoredArtifact::reference)
                .filter(reference -> reference.runId().equals(runId))
                .toList();
    }

    /** Content by key, for callers holding a key rather than a reference. */
    public synchronized Optional<byte[]> readByKey(String objectKey) {
        return readFile(resolveWithinRoot(objectKey));
    }

    /**
     * The key layout, matching what the S3 implementation will use.
     *
     * <p>{@code teams/{teamId}/runs/{runId}/...} — the team prefix first, so authorization is a
     * property of the key. A caller who may not read a team's data cannot construct a key inside it.
     */
    static String keyFor(ArtifactDescriptor descriptor) {
        StringBuilder key = new StringBuilder("teams/")
                .append(descriptor.teamId().value())
                .append("/runs/")
                .append(descriptor.runId().value())
                .append('/');

        descriptor.shardId()
                .ifPresentOrElse(
                        shard -> key.append("shards/").append(shard.value()).append('/'),
                        () -> key.append(sectionFor(descriptor.type())).append('/'));

        return key.append(descriptor.fileName()).toString();
    }

    private static String sectionFor(ArtifactType type) {
        return switch (type) {
            case EXECUTION_PLAN -> "plan";
            case REPORT -> "report";
            // Beside the plan rather than with the results: both are inputs to the run, and what a
            // run was given belongs together with what it executed.
            case DATASET -> "plan";
            case RESULTS, RUNNER_LOG, RUNNER_MANIFEST -> "analysis";
            // Only reached by a run-level snapshot, which nothing publishes today — a snapshot
            // belongs to the shard that wrote it and takes the shard prefix above. Named rather
            // than defaulted so adding a run-level one is a decision rather than an accident.
            case PROGRESS -> "analysis";
        };
    }

    /**
     * The shard a key belongs to, if any.
     *
     * <p>The key is the contract between the control plane and a runner — they share no filesystem,
     * no database and no process, and this layout is the only thing both agree on. A runner uploading
     * with an ordinary S3 client sets none of the platform's object metadata, so anything derived
     * from metadata alone would silently ignore everything a real runner produces.
     *
     * @return the shard identifier from {@code .../shards/{shardId}/...}, or empty for a run-level
     *         artifact
     */
    static Optional<RunnerShardId> shardIdIn(String objectKey) {
        String[] segments = objectKey.split("/");
        for (int i = 0; i + 1 < segments.length; i++) {
            if ("shards".equals(segments[i])) {
                try {
                    return Optional.of(RunnerShardId.of(java.util.UUID.fromString(segments[i + 1])));
                } catch (IllegalArgumentException e) {
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
    }

    /**
     * What kind of artifact a key holds, judged by its file name.
     *
     * <p>The names are fixed by the runner contract ({@code runner-images/jmeter-runner}), so this is
     * a real agreement rather than a guess. Anything unrecognised is empty rather than defaulted: an
     * unknown file adopted as {@code RESULTS} would be parsed as measurements and could quietly
     * change a verdict.
     */
    static Optional<ArtifactType> typeForFileName(String objectKey) {
        String fileName = objectKey.substring(objectKey.lastIndexOf('/') + 1)
                .toLowerCase(java.util.Locale.ROOT);

        // Checked before the results rule, and the reason this prefix exists at all: a dataset
        // partition is a CSV, and a CSV is also how JMeter results are stored. An input adopted as
        // a result would be parsed as measurements — thousands of rows of order identifiers read as
        // samples — and could change a verdict without anything looking wrong.
        if (fileName.startsWith(ArtifactStore.DATASET_OBJECT_PREFIX) && fileName.endsWith(".csv")) {
            return Optional.of(ArtifactType.DATASET);
        }
        if (fileName.endsWith(".jtl") || fileName.endsWith(".csv")) {
            return Optional.of(ArtifactType.RESULTS);
        }
        if (fileName.equals("runner-manifest.json")) {
            return Optional.of(ArtifactType.RUNNER_MANIFEST);
        }
        // A runner overwrites this every few seconds while it executes. Recognised by exact name for
        // the same reason the manifest is: these are the two files whose meaning is fixed by the
        // runner contract rather than by their extension.
        if (fileName.equals(com.perfplatform.domain.progress.ShardProgress.OBJECT_NAME)) {
            return Optional.of(ArtifactType.PROGRESS);
        }
        if (fileName.endsWith(".log")) {
            return Optional.of(ArtifactType.RUNNER_LOG);
        }
        if (fileName.endsWith(".jmx")) {
            return Optional.of(ArtifactType.EXECUTION_PLAN);
        }
        if (fileName.endsWith(".html") || fileName.endsWith(".html.gz") || fileName.equals("playwright-report.json") || fileName.equals("browser-evidence.tar.gz")) {
            return Optional.of(ArtifactType.REPORT);
        }
        return Optional.empty();
    }

    /**
     * Resolves a key under the root, refusing anything that would escape it.
     *
     * <p>Keys are constructed internally from identifiers, so this should be unreachable. It is here
     * because a path-traversal check that only exists where input is currently trusted is a check
     * that disappears the moment the input source changes.
     */
    private Path resolveWithinRoot(String objectKey) {
        Path resolved = root.resolve(objectKey).normalize();
        if (!resolved.startsWith(root)) {
            throw new ArtifactStoreException(
                    "Artifact key '" + objectKey + "' resolves outside the artifact root");
        }
        return resolved;
    }

    private static Optional<byte[]> readFile(Path file) {
        try {
            return Files.exists(file) ? Optional.of(Files.readAllBytes(file)) : Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String digestOf(byte[] content) {
        try {
            return "sha256:" + HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available in this JVM", e);
        }
    }

    /** Removes everything under the root. For tests and local cleanup only. */
    public synchronized void clear() {
        index.clear();
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder())
                    .filter(path -> !path.equals(root))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        } catch (IOException e) {
            throw new ArtifactStoreException("Could not clear the artifact root " + root, e);
        }
    }

    /** UTF-8 convenience for text artifacts. */
    public static byte[] utf8(String content) {
        return content == null ? new byte[0] : content.getBytes(StandardCharsets.UTF_8);
    }
}
