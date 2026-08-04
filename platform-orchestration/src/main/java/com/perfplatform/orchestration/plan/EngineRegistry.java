package com.perfplatform.orchestration.plan;

import com.perfplatform.engine.spi.ExecutionEngineProvider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The engines this deployment has.
 *
 * <p>One place that maps an engine identifier to its provider, so nothing else has to hold a list of
 * engines or decide what to do when a specification names one that is not installed. Orchestration
 * looks an engine up and calls the SPI; it never branches on which engine it got.
 */
public final class EngineRegistry implements com.perfplatform.domain.port.EngineCatalog {

    private final Map<String, ExecutionEngineProvider> byId;

    /**
     * @param providers every registered engine
     * @throws IllegalStateException if two providers claim the same identifier — that would make
     *                               plan compilation depend on bean ordering, which is the kind of
     *                               defect that appears only after a deployment reorders something
     */
    public EngineRegistry(List<ExecutionEngineProvider> providers) {
        Objects.requireNonNull(providers, "providers");

        Map<String, ExecutionEngineProvider> registered = new LinkedHashMap<>();
        for (ExecutionEngineProvider provider : providers) {
            String id = provider.engineId().toLowerCase(Locale.ROOT);
            ExecutionEngineProvider clash = registered.put(id, provider);
            if (clash != null) {
                throw new IllegalStateException("Two engine providers both claim the identifier '"
                        + id + "': " + clash.getClass().getName() + " and "
                        + provider.getClass().getName());
            }
        }
        this.byId = Map.copyOf(registered);
    }

    /** Raised when a specification or a stored plan names an engine that is not installed. */
    public static class UnknownEngineException extends RuntimeException {
        public UnknownEngineException(String engineId, Set<String> available) {
            super("No execution engine named '" + engineId + "' is registered. Available: "
                    + String.join(", ", available));
        }
    }

    public Optional<ExecutionEngineProvider> find(String engineId) {
        return engineId == null
                ? Optional.empty()
                : Optional.ofNullable(byId.get(engineId.toLowerCase(Locale.ROOT)));
    }

    /**
     * @throws UnknownEngineException if the engine is not installed. A stored plan naming a missing
     *                                engine is a real situation — an engine removed between a run
     *                                being queued and being claimed — and it must fail loudly rather
     *                                than fall back to whichever engine happens to be present
     */
    public ExecutionEngineProvider require(String engineId) {
        return find(engineId)
                .orElseThrow(() -> new UnknownEngineException(engineId, byId.keySet()));
    }

    /**
     * {@inheritDoc}
     *
     * <p>This is also what validation reports as available when a specification names an engine
     * this deployment does not have.
     */
    @Override
    public Set<String> engineIds() {
        return byId.keySet();
    }
}
