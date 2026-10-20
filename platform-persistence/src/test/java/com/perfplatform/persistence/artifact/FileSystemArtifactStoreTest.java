package com.perfplatform.persistence.artifact;

import com.perfplatform.domain.identity.RunnerShardId;
import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.identity.TestRunId;
import com.perfplatform.domain.port.ArtifactStore.ArtifactDescriptor;
import com.perfplatform.domain.port.ArtifactStore.ArtifactReference;
import com.perfplatform.domain.port.ArtifactStore.ArtifactType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class FileSystemArtifactStoreTest {

    private static final Instant NOW = Instant.parse("2026-08-03T10:15:30Z");

    @TempDir
    Path root;

    @Test
    @DisplayName("deleting an artifact removes the object and its local index entry")
    void deleteRemovesObjectAndIndexEntry() {
        FileSystemArtifactStore store =
                new FileSystemArtifactStore(root, Clock.fixed(NOW, ZoneOffset.UTC));
        TestRunId runId = TestRunId.generate();
        ArtifactReference stored = store.store(new ArtifactDescriptor(
                        TeamId.generate(),
                        runId,
                        Optional.of(RunnerShardId.generate()),
                        ArtifactType.RESULTS,
                        "results.jtl",
                        "text/csv"),
                "timeStamp,elapsed,label\n1,2,browse\n".getBytes(StandardCharsets.UTF_8));

        assertThat(store.read(stored)).isPresent();

        store.delete(stored);
        store.delete(stored);

        assertThat(store.read(stored)).isEmpty();
        assertThat(store.listForRun(runId)).isEmpty();
    }
}
