package com.perfplatform.persistence.dataset;

import com.perfplatform.domain.dataset.DatasetUri;
import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.port.ArtifactStore.ArtifactStoreException;
import com.perfplatform.domain.port.DatasetStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * Dataset content in a local directory.
 *
 * <p>For development, beside {@code FileSystemArtifactStore} and refused in production for the same
 * reason: a file one replica writes is invisible to the others and unreachable from a runner Pod.
 *
 * <p>Writes go to a temporary file and are moved into place. A partially written dataset read by a
 * partitioner would produce short slices, and the run would send less traffic than the test asked
 * for while reporting nothing unusual.
 */
public class FileSystemDatasetStore implements DatasetStore {

    private static final Logger log = LoggerFactory.getLogger(FileSystemDatasetStore.class);

    private final Path root;

    public FileSystemDatasetStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new ArtifactStoreException("Could not create the dataset root " + this.root, e);
        }
        log.info("Datasets are stored on the local filesystem at {}. Not durable across replicas "
                + "and unreachable from a runner Pod; set platform.orchestration.artifacts.store=s3 "
                + "for anything beyond local development.", this.root);
    }

    @Override
    public synchronized StoredContent store(TeamId teamId, DatasetUri uri, byte[] content) {
        byte[] body = content == null ? new byte[0] : content;
        String objectKey = DatasetKeys.keyFor(teamId, uri);
        Path file = resolveWithinRoot(objectKey);

        try {
            Files.createDirectories(file.getParent());
            Path temporary = Files.createTempFile(file.getParent(), ".partial-", ".tmp");
            Files.write(temporary, body);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new ArtifactStoreException("Could not store dataset " + objectKey, e);
        }

        return new StoredContent(objectKey, DatasetKeys.digestOf(body), body.length);
    }

    @Override
    public synchronized Optional<byte[]> read(String objectKey) {
        Path file = resolveWithinRoot(objectKey);
        try {
            return Files.isRegularFile(file) ? Optional.of(Files.readAllBytes(file)) : Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Resolves a key under the root, refusing anything that would escape it.
     *
     * <p>Keys here are built from identifiers and a validated name, so this should be unreachable.
     * It is present because a traversal check that exists only where input is currently trusted is a
     * check that disappears the moment the input source changes.
     */
    private Path resolveWithinRoot(String objectKey) {
        Path resolved = root.resolve(objectKey).normalize();
        if (!resolved.startsWith(root)) {
            throw new ArtifactStoreException(
                    "Dataset key '" + objectKey + "' resolves outside the dataset root");
        }
        return resolved;
    }
}
