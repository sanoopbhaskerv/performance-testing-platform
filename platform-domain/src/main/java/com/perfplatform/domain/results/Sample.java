package com.perfplatform.domain.results;

import java.util.Objects;

/**
 * One measured request.
 *
 * <p>The smallest engine-neutral unit of evidence. A JMeter sampler row, a k6 HTTP check and a
 * Playwright step all reduce to this, which is what lets one summarizer produce comparable
 * measurements for every engine.
 *
 * @param operation       what was exercised — the scenario or endpoint name. Carried per sample so
 *                        per-scenario objectives are evaluable, which V1 could not do because it
 *                        flattened scenarios away before results were ever written
 * @param elapsedMillis   how long it took, in whole milliseconds
 * @param success         whether the engine judged it successful
 * @param timestampMillis when it started, epoch milliseconds. Used only to bound the run's window
 *                        for throughput; never compared against the control plane's clock, which
 *                        belongs to a different machine
 * @param includeInAggregate whether this is a primary request/journey measurement. Secondary
 *                           steps and scores remain scoped evidence without diluting the headline
 */
public record Sample(String operation, long elapsedMillis, boolean success, long timestampMillis,
        boolean includeInAggregate) {

    /** Ordinary request samples contribute to headline latency and error rates. */
    public Sample(String operation, long elapsedMillis, boolean success, long timestampMillis) {
        this(operation, elapsedMillis, success, timestampMillis, true);
    }

    public Sample {
        Objects.requireNonNull(operation, "operation");
        if (elapsedMillis < 0) {
            throw new IllegalArgumentException("elapsedMillis must not be negative");
        }
    }
}
