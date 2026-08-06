package com.perfplatform.orchestration.execution;

import com.perfplatform.domain.execution.ExecutionPhase;
import com.perfplatform.domain.execution.ExecutionProvider;
import com.perfplatform.domain.execution.ProvisionRequest;
import com.perfplatform.domain.run.RunnerShard.ExecutionHandle;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Sends each shard to the provider that can run its engine.
 *
 * <p>The composition root previously chose one execution provider for the whole deployment, which
 * made a fact about a <em>shard</em> — which engine it runs — into a fact about the platform. A
 * hybrid run is the case that shows why that cannot hold: CLAUDE.md §21 describes one run where a
 * JMeter workload holds steady state while a small Playwright cohort walks through the UI, and those
 * two shards cannot both be executed by one engine-specific adapter.
 *
 * <p>Routing is by engine and nothing else. This class holds no policy: it does not decide whether a
 * run may start, how large it may be, or which team it belongs to — those are settled before a
 * {@link ProvisionRequest} exists. It only knows which adapter speaks which engine.
 *
 * <h2>Why handles carry their provider</h2>
 * {@link ExecutionHandle} records the provider that created it, so {@link #inspect} and
 * {@link #cancel} resolve back to the same adapter without consulting the run. That matters after a
 * control-plane restart: the handle is read from the database, and a shard whose provider was
 * inferred from current configuration could be inspected by an adapter that never started it —
 * which would report {@code NOT_FOUND} for a browser cohort that is still open, and free its
 * capacity slot while the load continued.
 */
public final class EngineRoutingExecutionProvider implements ExecutionProvider {

    public static final String PROVIDER_ID = "engine-routing";

    private final Map<String, ExecutionProvider> byEngine;
    private final Map<String, ExecutionProvider> byProviderId;

    /**
     * @param byEngine engine identifier to the adapter that runs it, e.g. {@code jmeter} to the
     *                 local JMeter adapter. Lookup is case-insensitive because an engine name
     *                 arrives from a specification, where casing is the author's choice.
     */
    public EngineRoutingExecutionProvider(Map<String, ExecutionProvider> byEngine) {
        Objects.requireNonNull(byEngine, "byEngine");
        if (byEngine.isEmpty()) {
            throw new IllegalArgumentException(
                    "A routing execution provider with no engines could run nothing");
        }

        Map<String, ExecutionProvider> engines = new LinkedHashMap<>();
        byEngine.forEach((engine, provider) ->
                engines.put(engine.toLowerCase(java.util.Locale.ROOT), provider));
        this.byEngine = Map.copyOf(engines);

        Map<String, ExecutionProvider> providers = new LinkedHashMap<>();
        byEngine.values().forEach(provider -> providers.put(provider.providerId(), provider));
        this.byProviderId = Map.copyOf(providers);
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    /**
     * True only if every routed adapter generates real load.
     *
     * <p>Conservative on purpose, and in the safe direction. A run marked synthetic reports
     * INDETERMINATE objectives, which understates a real result; a run wrongly marked material
     * reports a PASS for load that was never applied. Between a verdict that is too cautious and one
     * that is wrong, the platform takes the cautious one.
     */
    @Override
    public boolean generatesRealLoad() {
        return byEngine.values().stream().allMatch(ExecutionProvider::generatesRealLoad);
    }

    @Override
    public ProvisioningResult provision(ProvisionRequest request) {
        ExecutionProvider provider = byEngine.get(
                request.engine().toLowerCase(java.util.Locale.ROOT));

        if (provider == null) {
            // An outcome, not an exception: the run records it with a reason a consumer can act on.
            return ProvisioningResult.rejected(
                    "No execution provider in this deployment runs the '" + request.engine()
                            + "' engine. Configured engines: " + configuredEngines() + ".");
        }

        return provider.provision(request);
    }

    @Override
    public ExecutionStatus inspect(ExecutionHandle handle) {
        ExecutionProvider provider = byProviderId.get(handle.providerId());
        // UNKNOWN rather than NOT_FOUND. A handle whose adapter is no longer configured describes
        // something this deployment cannot see, not something that has finished — and guessing a
        // terminal phase would free a capacity slot while the workload was possibly still running.
        return provider == null
                ? ExecutionStatus.of(ExecutionPhase.UNKNOWN)
                : provider.inspect(handle);
    }

    @Override
    public CancellationResult cancel(ExecutionHandle handle) {
        ExecutionProvider provider = byProviderId.get(handle.providerId());
        return provider == null
                ? CancellationResult.pending(
                        "No configured provider owns handle provider '" + handle.providerId() + "'")
                : provider.cancel(handle);
    }

    private List<String> configuredEngines() {
        return byEngine.keySet().stream().sorted().toList();
    }
}
