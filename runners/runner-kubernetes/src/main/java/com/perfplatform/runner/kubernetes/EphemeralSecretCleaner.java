package com.perfplatform.runner.kubernetes;

import java.time.Instant;

/** Deletes expired runner-owned ephemeral Secrets. */
public interface EphemeralSecretCleaner {

    int deleteExpired(Instant now);
}
