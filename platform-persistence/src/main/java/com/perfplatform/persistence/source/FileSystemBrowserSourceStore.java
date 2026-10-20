package com.perfplatform.persistence.source;

import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.port.BrowserSourceStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/** Local-development source storage; the production composition root selects S3. */
public final class FileSystemBrowserSourceStore implements BrowserSourceStore {
    private final Path root;
    public FileSystemBrowserSourceStore(Path root) { this.root = root.toAbsolutePath().normalize(); }

    @Override
    public void put(TeamId team, String digest, byte[] content) {
        if (!BrowserSourceStore.digest(content).equals(digest)) throw new IllegalArgumentException("Source digest mismatch");
        Path file = root.resolve(BrowserSourceStore.key(team, digest));
        try {
            Files.createDirectories(file.getParent());
            Path temporary = Files.createTempFile(file.getParent(), ".upload-", ".tmp");
            Files.write(temporary, content);
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    @Override
    public Optional<byte[]> read(TeamId team, String digest) {
        Path file = root.resolve(BrowserSourceStore.key(team, digest));
        try { return Files.isRegularFile(file) ? Optional.of(Files.readAllBytes(file)) : Optional.empty(); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }
}
