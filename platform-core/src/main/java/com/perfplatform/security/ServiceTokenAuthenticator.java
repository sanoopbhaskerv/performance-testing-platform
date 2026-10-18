package com.perfplatform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.perfplatform.domain.port.TeamProvisioning;
import com.perfplatform.domain.security.CallerContext;
import com.perfplatform.domain.identity.TeamId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Standalone machine identities. Only token hashes live in an operator-mounted Secret file. */
@Component
public final class ServiceTokenAuthenticator {
    private final String tokenFile;
    private final TeamProvisioning teams;
    private final ObjectMapper mapper;
    private final java.util.concurrent.ConcurrentMap<String, TeamId> knownTeams = new java.util.concurrent.ConcurrentHashMap<>();
    public ServiceTokenAuthenticator(@Value("${platform.security.service-token-file:}") String tokenFile,
            TeamProvisioning teams, ObjectMapper mapper) {
        this.tokenFile = tokenFile; this.teams = teams; this.mapper = mapper;
        if (enabled() && !Files.isReadable(Path.of(tokenFile))) {
            throw new IllegalStateException("The configured service-token file is not readable.");
        }
    }
    public boolean enabled() { return tokenFile != null && !tokenFile.isBlank(); }
    public record Identity(String subject, String tokenSha256, Set<String> teams, Set<String> scopes, Instant expiresAt) {}

    /** Re-read on every request so replacing the mounted file revokes credentials immediately. */
    public Optional<CallerContext> authenticate(String token) {
        if (!enabled() || token == null || token.length() < 32 || token.length() > 512) return Optional.empty();
        try {
            Path file = Path.of(tokenFile);
            if (Files.size(file) > 1024 * 1024) return Optional.empty();
            List<Identity> identities = mapper.readValue(Files.readAllBytes(file),
                    mapper.getTypeFactory().constructCollectionType(List.class, Identity.class));
            byte[] actual = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            for (Identity identity : identities) {
                if (identity.tokenSha256() == null || !identity.tokenSha256().matches("[a-f0-9]{64}")) continue;
                if (!MessageDigest.isEqual(actual, HexFormat.of().parseHex(identity.tokenSha256()))) continue;
                if (identity.subject() == null || identity.subject().isBlank() || identity.expiresAt() == null
                        || !identity.expiresAt().isAfter(Instant.now()) || identity.teams() == null || identity.scopes() == null) return Optional.empty();
                Set<TeamId> memberships = new HashSet<>();
                for (String team : identity.teams()) {
                    if (!team.matches("[a-z0-9][a-z0-9-]{0,62}")) return Optional.empty();
                    // Team onboarding is authorized by this operator-owned identity file, never by
                    // a name in an untrusted test specification.
                    memberships.add(knownTeams.computeIfAbsent(team, teams::ensureTeam));
                }
                return Optional.of(new CallerContext(identity.subject(), memberships, identity.scopes(), true));
            }
        } catch (Exception ignored) {
            // Fail closed on a missing/replaced/malformed file. Never log the file or presented token.
        }
        return Optional.empty();
    }
}
