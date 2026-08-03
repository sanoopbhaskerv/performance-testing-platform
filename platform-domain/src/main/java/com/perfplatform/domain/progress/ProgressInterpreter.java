package com.perfplatform.domain.progress;

import com.perfplatform.domain.progress.ProgressObservation.Severity;
import com.perfplatform.domain.results.ObjectiveOutcome;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a run's live numbers into sentences, using rules rather than judgement.
 *
 * <p>Every message here is produced by an explicit threshold over an observed value, and carries
 * that value with it. That constraint is the whole design. The audience for a live view is somebody
 * who has never run a load test; they cannot tell a confident wrong statement from a right one, so
 * the platform must not be capable of making one. A model's opinion about a chart would be exactly
 * that capability.
 *
 * <p>A pure function of the series, the clock and the tolerance. No storage, no configuration
 * lookups, no memory between calls — so a message can be reproduced from the stored snapshots, and
 * a test can place a run in any condition without waiting for one.
 *
 * <h2>What it deliberately does not do</h2>
 * It does not predict, extrapolate a trend to a finish, or estimate whether a run "will pass". A
 * live view exists so a person can decide whether to keep watching or stop the test; a forecast
 * would replace their decision with arithmetic that has no basis in how the system under test
 * behaves at the next stage.
 */
public final class ProgressInterpreter {

    // ── Thresholds. Every one of these is named, so a message can point at the rule it came from. ──

    /** Below this many samples, an error rate is noise rather than a signal. */
    private static final long MIN_SAMPLES_FOR_ERROR_RATE = 20;

    /** Error rate, scaled by 1,000: 5%. */
    private static final long ERROR_RATE_ELEVATED_MILLI_PERCENT = 5_000;

    /** Error rate, scaled by 1,000: 25%. At this point the run is measuring a failure. */
    private static final long ERROR_RATE_CRITICAL_MILLI_PERCENT = 25_000;

    /** A recent window has to reach this before "rising" is worth saying at all. */
    private static final long ERROR_RATE_RISING_FLOOR_MILLI_PERCENT = 2_000;

    /** How long a run may report nothing before that itself is the finding. */
    private static final long SILENT_START_TOLERANCE_SECONDS = 60;

    /** Latency has to at least double before a knee is called. */
    private static final long LATENCY_KNEE_FACTOR = 2;

    /** Enough samples at both ends that a latency comparison means something. */
    private static final long MIN_SAMPLES_FOR_LATENCY_TREND = 50;

    /** Load climbing by this much, scaled to percent, before a plateau is worth reporting. */
    private static final long PLATEAU_LOAD_INCREASE_PERCENT = 25;

    /** Throughput moving less than this while load climbed is the plateau. */
    private static final long PLATEAU_THROUGHPUT_INCREASE_PERCENT = 5;

    // ── Codes. Stable, and each one is pinned by a test. ────────────────────────────────────────

    public static final String NO_LOAD_OBSERVED = "NO_LOAD_OBSERVED";
    public static final String WARMING_UP = "WARMING_UP";
    public static final String RUNNER_SILENT = "RUNNER_SILENT";
    public static final String SHARDS_NOT_REPORTING = "SHARDS_NOT_REPORTING";
    public static final String ERROR_RATE_CRITICAL = "ERROR_RATE_CRITICAL";
    public static final String ERROR_RATE_ELEVATED = "ERROR_RATE_ELEVATED";
    public static final String ERRORS_RISING = "ERRORS_RISING";
    public static final String LATENCY_DEGRADING = "LATENCY_DEGRADING";
    public static final String THROUGHPUT_PLATEAU = "THROUGHPUT_PLATEAU";
    public static final String OBJECTIVE_AT_RISK = "OBJECTIVE_AT_RISK";
    public static final String NOTHING_OF_CONCERN = "NOTHING_OF_CONCERN";

    private ProgressInterpreter() {
    }

    /**
     * Reads the series and says what it sees.
     *
     * @param series           merged snapshots oldest first; the last is the current view
     * @param liveObjectives   provisional objective outcomes over the latest snapshot, or empty
     * @param now              the reader's clock, for staleness
     * @param silenceTolerance how long a shard may go without publishing before it is called silent.
     *                         Comes from the same setting as the runner heartbeat tolerance, so the
     *                         two signals cannot disagree about what "lost" means
     * @return observations, most severe first, then in rule order. Never empty for a run that has a
     *         snapshot: saying nothing and having nothing to say look identical to a reader
     */
    public static List<ProgressObservation> interpret(List<LiveProgress> series,
            List<ObjectiveOutcome> liveObjectives, Instant now, Duration silenceTolerance) {

        if (series == null || series.isEmpty()) {
            return List.of();
        }

        LiveProgress latest = series.get(series.size() - 1);
        List<ProgressObservation> observations = new ArrayList<>();

        silence(latest, now, silenceTolerance).ifPresent(observations::add);
        missingShards(latest).ifPresent(observations::add);
        errorRate(latest).ifPresent(observations::add);
        errorsRising(series).ifPresent(observations::add);
        latencyKnee(series).ifPresent(observations::add);
        throughputPlateau(series).ifPresent(observations::add);
        objectivesAtRisk(liveObjectives).ifPresent(observations::add);
        noLoad(latest).ifPresent(observations::add);

        if (observations.isEmpty()) {
            observations.add(new ProgressObservation(NOTHING_OF_CONCERN, Severity.INFO,
                    "Nothing looks wrong so far.",
                    "%s requests measured, %s%% failing, p95 %sms."
                            .formatted(latest.overall().sampleCount(),
                                    percent(latest.overall().errorRateMilliPercent()),
                                    latest.overall().latency().percentileMillis(95,
                                            latest.overall().maxMillis()))));
        }

        observations.sort((left, right) -> right.severity().compareTo(left.severity()));
        return List.copyOf(observations);
    }

    /**
     * A shard that has stopped publishing.
     *
     * <p>The most important rule here, and the least obvious. Merged totals still climb when one of
     * two shards dies — the survivor keeps adding to them — so a run that lost half its load
     * generation looks, in the aggregate, like a run that is simply going a bit slower. Only the
     * per-shard capture time exposes it.
     */
    private static java.util.Optional<ProgressObservation> silence(LiveProgress latest, Instant now,
            Duration tolerance) {

        return latest.stalestShard()
                .filter(shard -> shard.ageSeconds(now) > tolerance.toSeconds())
                .map(shard -> new ProgressObservation(RUNNER_SILENT, Severity.CRITICAL,
                        "One of this run's load generators has stopped reporting. The totals below "
                                + "are still rising because the other generators are still going, "
                                + "so the run may now be applying less load than its plan describes.",
                        "Shard %d last reported %d seconds ago, past the %d-second tolerance."
                                .formatted(shard.shardIndex(), shard.ageSeconds(now),
                                        tolerance.toSeconds())));
    }

    private static java.util.Optional<ProgressObservation> missingShards(LiveProgress latest) {
        if (latest.isComplete()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new ProgressObservation(SHARDS_NOT_REPORTING, Severity.WARNING,
                "Not every load generator has reported yet. Until they all do, these numbers "
                        + "describe less load than the plan asks for.",
                "%d of %d shards have published a snapshot."
                        .formatted(latest.shardsReporting(), latest.shardsExpected())));
    }

    private static java.util.Optional<ProgressObservation> errorRate(LiveProgress latest) {
        ProgressMetrics overall = latest.overall();
        if (overall.sampleCount() < MIN_SAMPLES_FOR_ERROR_RATE) {
            return java.util.Optional.empty();
        }

        long rate = overall.errorRateMilliPercent();
        if (rate >= ERROR_RATE_CRITICAL_MILLI_PERCENT) {
            return java.util.Optional.of(new ProgressObservation(ERROR_RATE_CRITICAL,
                    Severity.CRITICAL,
                    "A quarter or more of the requests are failing. A failed request is usually "
                            + "answered faster than a real one, so the response times below are "
                            + "flattering rather than reassuring.",
                    "%s%% of %d requests have failed, against a %s%% rule."
                            .formatted(percent(rate), overall.sampleCount() + overall.unparsedCount(),
                                    percent(ERROR_RATE_CRITICAL_MILLI_PERCENT))));
        }
        if (rate >= ERROR_RATE_ELEVATED_MILLI_PERCENT) {
            return java.util.Optional.of(new ProgressObservation(ERROR_RATE_ELEVATED,
                    Severity.WARNING,
                    "Requests are failing more often than a healthy run usually does.",
                    "%s%% of %d requests have failed, against a %s%% rule."
                            .formatted(percent(rate), overall.sampleCount() + overall.unparsedCount(),
                                    percent(ERROR_RATE_ELEVATED_MILLI_PERCENT))));
        }
        return java.util.Optional.empty();
    }

    /**
     * Failures concentrated in the recent window rather than spread across the run.
     *
     * <p>Cumulative error rate is slow to move once a run has sent a lot of traffic: a system that
     * started failing outright a minute ago can still show 3% overall. Comparing the most recent
     * window against the run's own history is what makes that visible while it still matters.
     */
    private static java.util.Optional<ProgressObservation> errorsRising(List<LiveProgress> series) {
        if (series.size() < 2) {
            return java.util.Optional.empty();
        }

        LiveProgress latest = series.get(series.size() - 1);
        LiveProgress previous = series.get(series.size() - 2);
        LiveProgress earliest = series.get(0);

        long windowSamples = latest.overall().sampleCount() - previous.overall().sampleCount();
        long windowErrors = latest.overall().errorCount() - previous.overall().errorCount();
        if (windowSamples < MIN_SAMPLES_FOR_ERROR_RATE || windowErrors <= 0) {
            return java.util.Optional.empty();
        }

        long windowRate = (windowErrors * 100_000L) / windowSamples;
        long baselineRate = earliest.overall().errorRateMilliPercent();

        boolean rising = windowRate >= ERROR_RATE_RISING_FLOOR_MILLI_PERCENT
                && windowRate >= Math.max(baselineRate * 2, ERROR_RATE_RISING_FLOOR_MILLI_PERCENT);
        if (!rising) {
            return java.util.Optional.empty();
        }

        return java.util.Optional.of(new ProgressObservation(ERRORS_RISING, Severity.WARNING,
                "Failures are concentrated in the last few seconds rather than spread across the "
                        + "run. Something changed recently.",
                "%s%% of the most recent %d requests failed, against %s%% earlier in the run."
                        .formatted(percent(windowRate), windowSamples, percent(baselineRate))));
    }

    private static java.util.Optional<ProgressObservation> latencyKnee(List<LiveProgress> series) {
        if (series.size() < 2) {
            return java.util.Optional.empty();
        }

        LiveProgress latest = series.get(series.size() - 1);
        LiveProgress earliest = series.get(0);
        if (earliest.overall().sampleCount() < MIN_SAMPLES_FOR_LATENCY_TREND
                || latest.overall().sampleCount() < MIN_SAMPLES_FOR_LATENCY_TREND) {
            return java.util.Optional.empty();
        }

        long before = earliest.overall().latency()
                .percentileMillis(95, earliest.overall().maxMillis());
        long nowValue = latest.overall().latency().percentileMillis(95, latest.overall().maxMillis());
        if (before <= 0 || nowValue < before * LATENCY_KNEE_FACTOR) {
            return java.util.Optional.empty();
        }

        return java.util.Optional.of(new ProgressObservation(LATENCY_DEGRADING, Severity.WARNING,
                "Responses are taking substantially longer than they were earlier in this run. "
                        + "That usually means the system has passed a limit — a pool, a queue or a "
                        + "downstream dependency.",
                "p95 is now about %dms, against about %dms earlier in the run."
                        .formatted(nowValue, before)));
    }

    /**
     * More load, no more work done.
     *
     * <p>The classic saturation signature, and the one a novice is least likely to spot: adding users
     * stops adding throughput, so each user simply waits longer. Reported as information rather than
     * a warning, because it is often the answer the test was asked to find.
     */
    private static java.util.Optional<ProgressObservation> throughputPlateau(
            List<LiveProgress> series) {

        if (series.size() < 2) {
            return java.util.Optional.empty();
        }

        LiveProgress latest = series.get(series.size() - 1);
        LiveProgress earliest = series.get(0);

        long usersBefore = earliest.activeUsers();
        long throughputBefore = earliest.overall().throughputPerSecondMilli();
        if (usersBefore <= 0 || throughputBefore <= 0) {
            return java.util.Optional.empty();
        }

        long userIncrease = ((latest.activeUsers() - usersBefore) * 100) / usersBefore;
        long throughputIncrease =
                ((latest.overall().throughputPerSecondMilli() - throughputBefore) * 100)
                        / throughputBefore;

        if (userIncrease < PLATEAU_LOAD_INCREASE_PERCENT
                || throughputIncrease >= PLATEAU_THROUGHPUT_INCREASE_PERCENT) {
            return java.util.Optional.empty();
        }

        return java.util.Optional.of(new ProgressObservation(THROUGHPUT_PLATEAU, Severity.INFO,
                "Adding users has stopped adding throughput. The system is completing about as much "
                        + "work as it did with fewer users, so each user is waiting longer.",
                "Active users rose %d%% while throughput moved %d%% (%s to %s requests/second)."
                        .formatted(userIncrease, throughputIncrease,
                                rate(throughputBefore),
                                rate(latest.overall().throughputPerSecondMilli()))));
    }

    private static java.util.Optional<ProgressObservation> objectivesAtRisk(
            List<ObjectiveOutcome> liveObjectives) {

        if (liveObjectives == null || liveObjectives.isEmpty()) {
            return java.util.Optional.empty();
        }

        List<ObjectiveOutcome> breaching = liveObjectives.stream()
                .filter(outcome -> outcome.verdict() == ObjectiveOutcome.Verdict.BREACH)
                .toList();
        if (breaching.isEmpty()) {
            return java.util.Optional.empty();
        }

        ObjectiveOutcome first = breaching.get(0);
        return java.util.Optional.of(new ProgressObservation(OBJECTIVE_AT_RISK, Severity.WARNING,
                breaching.size() == 1
                        ? "One of this test's objectives is not being met at the moment."
                        : breaching.size() + " of this test's objectives are not being met at the "
                                + "moment.",
                // The provisional caveat is in the evidence rather than the message, because the
                // evidence is what someone quotes when they escalate. A live breach is measured from
                // partial output with bucketed percentiles; the run's verdict is computed later from
                // the complete raw results and is the only one that decides pass or fail.
                "%s: %s This is measured from partial results and is not the run's verdict."
                        .formatted(first.metric(), first.detail())));
    }

    /**
     * A run that is executing and measuring nothing.
     *
     * <p>Last in the list because it is the least likely, and the most alarming when true: a runner
     * that started, is alive, and has produced no samples at all is usually pointed at something it
     * cannot reach. Before the tolerance it is reported as warm-up, because image pulls and JVM start
     * are genuinely a minute of nothing.
     */
    private static java.util.Optional<ProgressObservation> noLoad(LiveProgress latest) {
        if (latest.overall().sampleCount() > 0) {
            return java.util.Optional.empty();
        }

        if (latest.elapsedSeconds() < SILENT_START_TOLERANCE_SECONDS) {
            return java.util.Optional.of(new ProgressObservation(WARMING_UP, Severity.INFO,
                    "The generators have started and no requests have completed yet. This is normal "
                            + "for the first few seconds.",
                    "%d seconds elapsed, no samples yet.".formatted(latest.elapsedSeconds())));
        }

        return java.util.Optional.of(new ProgressObservation(NO_LOAD_OBSERVED, Severity.CRITICAL,
                "The run is executing but no request has completed. Nothing is being measured — "
                        + "usually the target cannot be reached, or every request is timing out.",
                "%d seconds elapsed with no samples, past the %d-second tolerance."
                        .formatted(latest.elapsedSeconds(), SILENT_START_TOLERANCE_SECONDS)));
    }

    /** A scaled milli-percent as a readable percentage: {@code 12400} becomes {@code 12.4}. */
    private static String percent(long milliPercent) {
        return BigDecimal.valueOf(milliPercent, 3).stripTrailingZeros().toPlainString();
    }

    /** A scaled milli-rate as a readable rate: {@code 21400} becomes {@code 21.4}. */
    private static String rate(long milliRate) {
        return BigDecimal.valueOf(milliRate, 3).stripTrailingZeros().toPlainString();
    }
}
