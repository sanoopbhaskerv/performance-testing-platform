package com.perfplatform.api.v2.dto;

import com.perfplatform.domain.progress.LiveProgress;
import com.perfplatform.domain.progress.ProgressMetrics;
import com.perfplatform.orchestration.run.ReadRunProgressUseCase.LiveView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * What a run is doing right now.
 *
 * <p>Every field here is provisional and the response says so in three places — a flag, the
 * objectives' own name, and the wording of each objective's detail. That repetition is deliberate.
 * A number from a running test travels: it gets screenshotted, pasted into a chat and quoted in a
 * status update, and by the time it arrives somewhere it has usually lost whatever caveat sat beside
 * it on the screen. The caveat has to be in the thing that travels.
 *
 * @param runId                 the run
 * @param engine                which engine's shards reported
 * @param capturedAt            the freshest shard capture in this view, by the runner's clock
 * @param observedAt            the control plane's clock when this response was assembled, so a
 *                              reader can tell a stale response from a stale run
 * @param ageSeconds            how old the freshest number is
 * @param shardsExpected        how many generators the plan calls for
 * @param shardsReporting       how many have published anything
 * @param elapsedSeconds        how long the longest-running generator has been executing
 * @param activeUsers           virtual users or browser sessions applying load, as the engines
 *                              report it — what is actually running, not what the stage asked for
 * @param overall               every operation together
 * @param byOperation           each operation on its own
 * @param shards                each generator's last word, which the totals cannot show
 * @param series                the retained history, oldest first
 * @param provisionalObjectives each objective judged against the latest snapshot. <b>Not</b> the
 *                              run's verdict, which is computed from the complete results after
 *                              every shard has finished
 * @param observations          what the platform's rules make of it, most severe first
 */
@Schema(description = "Live measurements from a run that is still executing. Provisional: "
        + "percentiles are bucket upper bounds, counts come from partial output, and no value here "
        + "is a run's verdict.")
public record RunProgressResponse(
        String runId,
        String engine,
        Instant capturedAt,
        Instant observedAt,
        long ageSeconds,
        int shardsExpected,
        int shardsReporting,
        long elapsedSeconds,
        long activeUsers,
        MetricsResponse overall,
        List<MetricsResponse> byOperation,
        List<ShardResponse> shards,
        List<PointResponse> series,
        List<ObjectiveOutcomeResponse> provisionalObjectives,
        List<ObservationResponse> observations) {

    /**
     * One operation's live measurements.
     *
     * @param errorRateMilliPercent error rate as a percentage scaled by 1,000, so 1500 is 1.5%. The
     *                              same scaled form the final report uses, so the two are directly
     *                              comparable rather than nearly comparable
     * @param p95Millis             the 95th percentile as an <b>upper bound</b>: the top of the
     *                              bucket the sample fell in. Never interpolated, because an
     *                              interpolated value is a plausible number no sample supports
     */
    @Schema(description = "Live measurements for one operation, or for the whole run")
    public record MetricsResponse(
            String name,
            long sampleCount,
            long errorCount,
            long unparsedCount,
            long errorRateMilliPercent,
            long minMillis,
            long maxMillis,
            long meanMillis,
            long p50Millis,
            long p90Millis,
            long p95Millis,
            long p99Millis,
            long throughputPerSecondMilli) {

        static MetricsResponse from(ProgressMetrics metrics) {
            var normalized = metrics.toOperationMetrics();
            return new MetricsResponse(
                    metrics.name(),
                    metrics.sampleCount(),
                    metrics.errorCount(),
                    metrics.unparsedCount(),
                    metrics.errorRateMilliPercent(),
                    metrics.minMillis(),
                    metrics.maxMillis(),
                    metrics.meanMillis(),
                    normalized.p50Millis(),
                    normalized.p90Millis(),
                    normalized.p95Millis(),
                    normalized.p99Millis(),
                    metrics.throughputPerSecondMilli());
        }
    }

    /**
     * One generator's last word.
     *
     * <p>Present because the merged totals cannot show a generator that stopped: the survivors keep
     * adding to them, so a run that lost half its load looks like a run that is merely slower.
     *
     * @param ageSeconds how long since this shard published anything
     */
    @Schema(description = "One load generator's own last snapshot")
    public record ShardResponse(
            int shardIndex,
            Instant capturedAt,
            long ageSeconds,
            long sampleCount,
            long errorCount,
            long activeUsers) {
    }

    /**
     * One point in the history, reduced to what a chart needs.
     *
     * <p>Deliberately not the whole snapshot per point. A client drawing thirty minutes of a run
     * needs a few numbers per point and not the per-operation breakdown of each, and shipping the
     * latter would make the response grow with the run's length for nothing anybody reads.
     */
    @Schema(description = "One point in a run's live history")
    public record PointResponse(
            Instant capturedAt,
            long elapsedSeconds,
            long activeUsers,
            long sampleCount,
            long errorCount,
            long errorRateMilliPercent,
            long throughputPerSecondMilli,
            long p95Millis) {
    }

    /**
     * One thing the rules noticed, and the measurement behind it.
     *
     * @param evidence the observation the message came from, with its numbers. Always present:
     *                 a message without it is an opinion, and this is read by people deciding
     *                 whether to stop a running test
     */
    @Schema(description = "A rule-based observation about a running test, with its evidence")
    public record ObservationResponse(
            String code,
            String severity,
            String message,
            String evidence) {
    }

    public static RunProgressResponse from(LiveView view) {
        LiveProgress latest = view.latest();

        List<MetricsResponse> operations = latest.byOperation().values().stream()
                .map(MetricsResponse::from)
                .toList();

        List<ShardResponse> shards = latest.shards().stream()
                .map(shard -> new ShardResponse(
                        shard.shardIndex(),
                        shard.capturedAt(),
                        shard.ageSeconds(view.observedAt()),
                        shard.sampleCount(),
                        shard.errorCount(),
                        shard.activeUsers()))
                .toList();

        List<PointResponse> series = view.series().stream()
                .map(point -> new PointResponse(
                        point.capturedAt(),
                        point.elapsedSeconds(),
                        point.activeUsers(),
                        point.overall().sampleCount(),
                        point.overall().errorCount(),
                        point.overall().errorRateMilliPercent(),
                        point.overall().throughputPerSecondMilli(),
                        point.overall().latency()
                                .percentileMillis(95, point.overall().maxMillis())))
                .toList();

        List<ObservationResponse> observations = view.observations().stream()
                .map(observation -> new ObservationResponse(
                        observation.code(),
                        observation.severity().name(),
                        observation.message(),
                        observation.evidence()))
                .toList();

        return new RunProgressResponse(
                latest.runId().toString(),
                latest.engine(),
                latest.capturedAt(),
                view.observedAt(),
                latest.ageSeconds(view.observedAt()),
                latest.shardsExpected(),
                latest.shardsReporting(),
                latest.elapsedSeconds(),
                latest.activeUsers(),
                MetricsResponse.from(latest.overall()),
                operations,
                shards,
                series,
                view.provisionalObjectives().stream()
                        .map(ObjectiveOutcomeResponse::from)
                        .toList(),
                observations);
    }
}
