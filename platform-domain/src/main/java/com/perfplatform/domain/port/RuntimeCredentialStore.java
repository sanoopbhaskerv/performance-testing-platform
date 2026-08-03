package com.perfplatform.domain.port;

import com.perfplatform.domain.execution.RuntimeCredential;
import com.perfplatform.domain.identity.TestRunId;

import java.util.List;

/**
 * Run-scoped credential material held outside durable storage.
 *
 * <p>This is deliberately an in-memory seam for the development/local path. A production secret
 * manager can implement the same port later; S5 does not choose one.
 */
public interface RuntimeCredentialStore {

    void put(TestRunId runId, List<RuntimeCredential> credentials);

    List<RuntimeCredential> find(TestRunId runId);

    void clear(TestRunId runId);
}
