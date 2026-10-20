package com.perfplatform.orchestration.run;

import com.perfplatform.domain.execution.RuntimeCredential;
import com.perfplatform.domain.identity.TestRunId;
import com.perfplatform.domain.port.RuntimeCredentialStore;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Development implementation of run-scoped runtime credentials. */
public final class InMemoryRuntimeCredentialStore implements RuntimeCredentialStore {

    private final Map<TestRunId, List<RuntimeCredential>> credentials = new ConcurrentHashMap<>();

    @Override
    public void put(TestRunId runId, List<RuntimeCredential> values) {
        credentials.put(runId, values == null ? List.of() : List.copyOf(values));
    }

    @Override
    public List<RuntimeCredential> find(TestRunId runId) {
        return credentials.getOrDefault(runId, List.of());
    }

    @Override
    public void clear(TestRunId runId) {
        credentials.remove(runId);
    }
}
