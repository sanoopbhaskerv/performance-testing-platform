package com.perfplatform.persistence.dataset;

import com.perfplatform.domain.dataset.DatasetUri;
import com.perfplatform.domain.identity.TeamId;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The dataset key layout, in one place so the two stores cannot drift.
 *
 * <p>{@code teams/{teamId}/datasets/{name}/{version}/data.csv} — the team first, as with artifacts,
 * because that is what makes authorization a property of the key rather than a lookup somebody has
 * to remember to perform. A filesystem store and an S3 store that computed keys separately would
 * eventually differ, and a dataset written under one layout and read under the other is a run that
 * finds no data.
 */
public final class DatasetKeys {

    /** The file name inside a version's prefix. Fixed, so the key is derivable from the reference. */
    static final String CONTENT_FILE = "data.csv";

    private DatasetKeys() {
    }

    /** Where one version of a team's dataset lives. */
    public static String keyFor(TeamId teamId, DatasetUri uri) {
        return "teams/%s/datasets/%s/%d/%s".formatted(
                teamId.value(), uri.name(), uri.version(), CONTENT_FILE);
    }

    /** SHA-256 over the content, in the same {@code sha256:<hex>} form artifacts use. */
    public static String digestOf(byte[] content) {
        try {
            return "sha256:" + HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available in this JVM", e);
        }
    }
}
