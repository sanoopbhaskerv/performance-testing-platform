package com.perfplatform.persistence.source;
import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.port.BrowserSourceStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;

class BrowserSourceStoreTest {
    @TempDir Path root;
    @Test void contentIsRecoverableOnlyInTheOwningTeamsNamespace() {
        var store = new FileSystemBrowserSourceStore(root);
        var team = TeamId.generate(); byte[] body = "reviewed source".getBytes();
        String digest = BrowserSourceStore.digest(body); store.put(team, digest, body);
        assertThat(store.read(team, digest)).hasValue(body);
        assertThat(store.read(TeamId.generate(), digest)).isEmpty();
        assertThatThrownBy(() -> BrowserSourceStore.requireOwned(TeamId.generate(), BrowserSourceStore.uri(team, digest), digest))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void substitutionsAndInvalidDigestPathsAreRejected() {
        var store = new FileSystemBrowserSourceStore(root); var team = TeamId.generate();
        String digest = BrowserSourceStore.digest("approved".getBytes());
        assertThatThrownBy(() -> store.put(team, digest, "changed".getBytes())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.read(team, "../../escape")).isInstanceOf(IllegalArgumentException.class);
    }
}
