package com.perfplatform.domain.port;

import com.perfplatform.domain.identity.TestRunId;
import com.perfplatform.domain.progress.LiveProgress;

import java.util.List;
import java.util.Optional;

/**
 * Durable storage for what a run looked like while it was still running.
 *
 * <p>Kept rather than held in memory, for the same reason the run state machine is: a control-plane
 * restart must not be an event. A live view assembled in a replica's heap disappears with the
 * replica, and the moment somebody most wants to know what a run was doing is after something went
 * wrong with it.
 *
 * <p>The series is the reason this is not a single upserted row. Rate of change is the signal — an
 * error rate that has doubled in the last minute, a p95 that has passed a knee, throughput that
 * stopped climbing when users did not. A latest-only view can show a number but never a trend, and
 * the trend is what tells somebody whether to stop the test.
 *
 * <h2>Derived, and disposable</h2>
 * Everything here is reduced from runner output that is itself stored as evidence. Losing it costs a
 * chart, never a verdict: a run's result is what the analyzer computes from the complete raw results
 * after every shard has finished. That is what makes it safe to prune this on a retention schedule
 * while artifacts are kept.
 */
public interface ProgressCatalog {

    /**
     * Appends a merged snapshot.
     *
     * <p>Idempotent by capture time: reconciliation repeats, and a pass that re-reads the same runner
     * snapshot must not add a second identical point to the series. Implementations record the newer
     * capture and ignore one that is not newer, so a slow pass cannot rewind a run's history.
     */
    void record(LiveProgress progress);

    /** The most recent merged snapshot, or empty if the run has never reported. */
    Optional<LiveProgress> latest(TestRunId runId);

    /**
     * The retained series, oldest first, capped at {@code limit} points.
     *
     * <p>When more points exist than the limit allows, implementations return the <em>oldest</em>
     * point and the most recent ones rather than a naive tail. The trend rules compare now against
     * the beginning of the run, and a window that has forgotten the beginning cannot say that
     * anything has changed.
     */
    List<LiveProgress> series(TestRunId runId, int limit);

    /** Removes a run's series. For retention, never as part of reaching a terminal state. */
    void clear(TestRunId runId);
}
