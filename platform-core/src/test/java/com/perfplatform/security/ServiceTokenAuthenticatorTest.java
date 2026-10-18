package com.perfplatform.security;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.perfplatform.domain.port.TeamProvisioning;
import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.port.BrowserSourceStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ServiceTokenAuthenticatorTest {
    @TempDir Path dir;
    final String token = "test-only-not-a-real-token-01234567890123456789";
    final TeamProvisioning teams = mock(TeamProvisioning.class);
    ServiceTokenAuthenticator auth(String expires) throws Exception {
        when(teams.ensureTeam("alpha")).thenReturn(TeamId.generate());
        Path file = dir.resolve("identities.json");
        Files.writeString(file, """
          [{"subject":"ci:alpha","tokenSha256":"%s","teams":["alpha"],"scopes":["runs:read"],"expiresAt":"%s"}]
          """.formatted(BrowserSourceStore.digest(token.getBytes()).substring(7), expires));
        return new ServiceTokenAuthenticator(file.toString(), teams, new ObjectMapper().registerModule(new JavaTimeModule()));
    }
    @Test void mapsOnlyTheConfiguredTeamAndScopes() throws Exception {
        var caller = auth(Instant.now().plusSeconds(60).toString()).authenticate(token).orElseThrow();
        assertThat(caller.subject()).isEqualTo("ci:alpha"); assertThat(caller.scopes()).containsExactly("runs:read");
        assertThat(caller.teamIds()).hasSize(1); assertThat(caller.serviceIdentity()).isTrue();
    }
    @Test void expiredOrUnknownTokenNeverCreatesATeam() throws Exception {
        var auth = auth(Instant.now().minusSeconds(60).toString());
        assertThat(auth.authenticate(token)).isEmpty(); assertThat(auth.authenticate(token+"wrong")).isEmpty();
        verify(teams, never()).ensureTeam(anyString());
    }
    @Test void mountedFileReplacementRevokesImmediatelyAndMalformedFilesFailClosed() throws Exception {
        var auth = auth(Instant.now().plusSeconds(60).toString());
        assertThat(auth.authenticate(token)).isPresent();
        Files.writeString(dir.resolve("identities.json"), "[]"); assertThat(auth.authenticate(token)).isEmpty();
        Files.writeString(dir.resolve("identities.json"), "malformed"); assertThat(auth.authenticate(token)).isEmpty();
    }
}
