package com.perfplatform.domain.port;

import com.perfplatform.domain.identity.TeamId;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/** Immutable, team-owned reviewed scripts, uploaded before a run exists. */
public interface BrowserSourceStore {
    void put(TeamId team, String digest, byte[] content);
    Optional<byte[]> read(TeamId team, String digest);

    static String digest(byte[] content) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String key(TeamId team, String digest) {
        if (digest == null || !digest.matches("sha256:[a-f0-9]{64}")) {
            throw new IllegalArgumentException("A browser source needs a SHA-256 digest.");
        }
        return "teams/" + team + "/sources/playwright/" + digest.substring(7) + "/journey.spec.ts";
    }

    static String uri(TeamId team, String digest) {
        return "artifact://" + key(team, digest);
    }

    /** Exact comparison prevents a submitted source from reading another team's objects. */
    static void requireOwned(TeamId team, String uri, String digest) {
        if (!uri(team, digest).equals(uri)) {
            throw new IllegalArgumentException("Browser source must be an uploaded script owned by this team.");
        }
    }
}
