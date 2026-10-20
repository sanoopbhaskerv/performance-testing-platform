package com.perfplatform.orchestration;

import java.time.Duration;
import java.util.Objects;

/**
 * The timing and batching knobs reconciliation runs on.
 *
 * <p>Grouped into one value object rather than scattered as constructor arguments so that the
 * relationships between them stay visible — several are only safe in relation to another, and a
 * deployment that tunes one without the other gets a subtly broken control loop.
 *
 * @param leaseDuration       how long a claim holds before another reconciler may take the run over.
 *                            Must comfortably exceed the reconciliation interval, or a reconciler
 *                            will lose runs it is actively working on to a peer mid-provision
 * @param heartbeatTolerance  how long a shard may go silent before it is declared lost. Too short
 *                            fails healthy runs during a slow GC pause or a node hiccup; too long
 *                            leaves a dead run holding a capacity slot
 * @param executionGrace      added to the plan's duration to form the runner deadline. Covers
 *                            startup, ramp-down and artifact upload, which are real and not in the
 *                            plan's stage arithmetic
 * @param claimBatchSize      most runs one reconciliation pass will claim, bounding how long a pass
 *                            can take and therefore how stale a lease can get
 * @param validationGrace     how long a run may sit unvalidated before reconciliation re-decides it.
 *                            Only reached when a control plane died between creating a run and
 *                            recording its validation outcome
 */
public record OrchestrationSettings(
        Duration leaseDuration,
        Duration heartbeatTolerance,
        Duration executionGrace,
        int claimBatchSize,
        Duration validationGrace) {

    /**
     * Defaults sized for the documented load: 3–4 concurrent runs normally, 10 at peak, each lasting
     * minutes to hours. Every value is generous relative to a reconciliation interval measured in
     * seconds, because the cost of being slightly slow is latency while the cost of being too eager
     * is a duplicated or abandoned load test.
     */
    public static final OrchestrationSettings DEFAULTS = new OrchestrationSettings(
            Duration.ofMinutes(2),
            Duration.ofMinutes(5),
            Duration.ofMinutes(10),
            20,
            Duration.ofMinutes(1));

    public OrchestrationSettings {
        requirePositive(leaseDuration, "leaseDuration");
        requirePositive(heartbeatTolerance, "heartbeatTolerance");
        requirePositive(executionGrace, "executionGrace");
        requirePositive(validationGrace, "validationGrace");
        if (claimBatchSize < 1) {
            throw new IllegalArgumentException("claimBatchSize must be at least 1");
        }
    }

    private static void requirePositive(Duration value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }
}
