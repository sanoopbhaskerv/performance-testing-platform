package com.perfplatform.api.v2.dto;

import com.perfplatform.domain.report.RunReport;
import com.perfplatform.domain.results.NormalizedRunResult;
import com.perfplatform.domain.results.ObjectiveOutcome.Verdict;
import com.perfplatform.domain.results.OperationMetrics;
import com.perfplatform.domain.spec.PerformanceTestSpecification;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.ArrayList;
import java.util.List;

/**
 * A run's report.
 *
 * <p>JSON, not HTML. V1 rendered a report server-side and persisted the file path as a durable
 * reference, which is how its run history filled with links nobody could open. Returning data lets
 * the console render it, a pipeline assert on it and a comparison read two of them — none of which is
 * possible with a page of markup.
 *
 * <h2>Nothing here is fabricated when it is missing</h2>
 * {@code measurements} is null until the run has been analyzed, rather than an object full of zeros.
 * {@code execution} is null until the run was admitted and compiled. A consumer can therefore say
 * "not measured yet" instead of drawing a chart of a test that never ran.
 *
 * @param run           the run's state, including its synthetic flag
 * @param synthetic     repeated at the top level, deliberately. This is the caveat on every number
 *                      below it, and a client that reads {@code report.synthetic} must not have to
 *                      know to look inside {@code run} for it
 * @param syntheticNotice the sentence to display when {@code synthetic} is true; null otherwise
 * @param test          the immutable test that was executed, null if that version is unreadable
 * @param execution     how it was compiled and what it was expected to cost, null until admitted
 * @param objectives    the stored verdicts, empty until analysis has run
 * @param summary       counts by verdict, so a caller does not have to derive them to show a headline
 * @param measurements  what was measured, null until analysis has run
 * @param timeline      the run's history, in order
 * @param artifacts     the evidence, by reference. Content is a separate authorized request
 */
@Schema(description = "Everything known about one run: verdicts, measurements, history and evidence")
public record RunReportResponse(
        TestRunResponse run,
        @Schema(description = "True when no load was generated. Every measurement below is invented "
                + "and no objective can pass.")
        boolean synthetic,
        String syntheticNotice,
        TestedDefinitionResponse test,
        ExecutionResponse execution,
        List<ObjectiveOutcomeResponse> objectives,
        ObjectiveSummaryResponse summary,
        MeasurementsResponse measurements,
        List<RunEventResponse> timeline,
        List<ArtifactResponse> artifacts) {

    /**
     * What a synthetic run's report says, at the top, in one sentence.
     *
     * <p>Deliberately blunt and deliberately free of hedging. This text is the thing that has to
     * survive being screenshotted into a ticket alongside a latency number.
     */
    public static final String SYNTHETIC_NOTICE =
            "No load was generated. These measurements are synthetic and describe nothing.";

    /**
     * The immutable test a run executed, in consumer language.
     *
     * <p>Carries the workload profile — expected users, ramp-up, journey mix — and not the engine's
     * view of it. The engine is named under {@code execution} for an expert reading the advanced
     * detail, which is where CLAUDE.md §24 permits it.
     */
    @Schema(description = "The immutable test this run executed")
    public record TestedDefinitionResponse(
            String name,
            String application,
            int versionNumber,
            String digest,
            List<WorkloadProfileResponse> workloads) {
    }

    /**
     * One workload's shape over time.
     *
     * @param stages    hold or ramp toward {@code targetUsers} for STAGES or {@code targetRate}
     *                  for ARRIVAL_RATE. A final stage targeting zero is ramp-down
     * @param scenarios the journey mix, with each journey's share of iterations
     */
    @Schema(description = "How one workload's load is shaped over time")
    public record WorkloadProfileResponse(
            String name,
            @Schema(description = "PROTOCOL for API load, BROWSER for a real-browser journey")
            String type,
            int peakUsers,
            Integer iterationsPerUser,
            @Schema(description = "STAGES for concurrent users, ARRIVAL_RATE for requests per second; null for browsers")
            String modelType,
            Integer maxConcurrency,
            int peakArrivalRate,
            long totalDurationSeconds,
            List<StageResponse> stages,
            List<ScenarioResponse> scenarios) {
    }

    /** One stage of a load profile. */
    @Schema(description = "One stage of a load profile")
    public record StageResponse(long durationSeconds, int targetUsers, Integer targetRate) {
    }

    /** One journey in the mix, and its share. */
    @Schema(description = "One journey in the mix and its share of iterations")
    public record ScenarioResponse(String name, int weightPercent, String operationId) {
    }

    /**
     * What the platform compiled, for an expert.
     *
     * <p>The plan digest answers "are these results from the test that was approved" — results whose
     * digest does not match the run's plan are rejected rather than merged, and omitting it here would
     * ask a reader to take that check on trust.
     */
    @Schema(description = "The compiled execution plan's identity and expected cost")
    public record ExecutionResponse(
            String engine,
            String planDigest,
            int shardCount,
            int peakVirtualUsers,
            long estimatedDurationSeconds) {
    }

    /**
     * Counts by verdict.
     *
     * @param demonstrated true only when the run was analyzed, generated real load, asked at least
     *                     one question and every answer was a pass. Anything less is not a pass that
     *                     was shown
     */
    @Schema(description = "Objective counts by verdict")
    public record ObjectiveSummaryResponse(
            long passed,
            long breached,
            long indeterminate,
            boolean demonstrated) {
    }

    /**
     * What was measured.
     *
     * @param complete       whether every runner shard the plan called for reported. When false, the
     *                       measurements describe less load than was planned and no objective passes
     * @param overall        the aggregate across every operation, which whole-test objectives use
     * @param operations     per-operation measurements, in a stable order
     */
    @Schema(description = "The measurements a run produced, after engine output was normalized")
    public record MeasurementsResponse(
            String engine,
            java.time.Instant measuredFrom,
            java.time.Instant measuredTo,
            int shardsExpected,
            int shardsReported,
            boolean complete,
            OperationMetricsResponse overall,
            List<OperationMetricsResponse> operations) {
    }

    /**
     * One operation's measurements, in consumer units.
     *
     * <p>Latencies are whole milliseconds. Throughput and error rate are given as decimals here
     * because they are for display; the platform's own comparisons use the scaled integers, which is
     * what keeps a verdict from depending on floating-point rounding.
     *
     * @param unparsedCount  requests whose result could not be read at all. Included in the error-rate
     *                       denominator, and reported separately so a reader can see it was
     *                       — V1 excluded them, which improved the error rate the worse a run went
     */
    @Schema(description = "One operation's measurements")
    public record OperationMetricsResponse(
            String name,
            long sampleCount,
            long errorCount,
            long unparsedCount,
            double errorRatePercent,
            long minMillis,
            long maxMillis,
            long meanMillis,
            long p50Millis,
            long p90Millis,
            long p95Millis,
            long p99Millis,
            double throughputPerSecond,
            Long p75Millis) {

        static OperationMetricsResponse from(OperationMetrics metrics) {
            return new OperationMetricsResponse(
                    metrics.name(),
                    metrics.sampleCount(),
                    metrics.errorCount(),
                    metrics.unparsedCount(),
                    metrics.errorRateMilliPercent() / 1000.0,
                    metrics.minMillis(),
                    metrics.maxMillis(),
                    metrics.meanMillis(),
                    metrics.p50Millis(),
                    metrics.p90Millis(),
                    metrics.p95Millis(),
                    metrics.p99Millis(),
                    metrics.throughputPerSecond(), metrics.p75Millis());
        }
    }

    public static RunReportResponse from(RunReport report) {
        boolean synthetic = report.isSynthetic();

        return new RunReportResponse(
                TestRunResponse.from(report.run()),
                synthetic,
                synthetic ? SYNTHETIC_NOTICE : null,
                report.definition().map(RunReportResponse::testFrom).orElse(null),
                report.execution().map(RunReportResponse::executionFrom).orElse(null),
                report.objectives().stream().map(ObjectiveOutcomeResponse::from).toList(),
                new ObjectiveSummaryResponse(
                        report.objectivesWith(Verdict.PASS),
                        report.objectivesWith(Verdict.BREACH),
                        report.objectivesWith(Verdict.INDETERMINATE),
                        report.demonstratedItsObjectives()),
                report.metrics().map(RunReportResponse::measurementsFrom).orElse(null),
                report.timeline().stream().map(RunEventResponse::from).toList(),
                report.artifacts().stream().map(ArtifactResponse::from).toList());
    }

    private static TestedDefinitionResponse testFrom(RunReport.TestedDefinition definition) {
        return new TestedDefinitionResponse(
                definition.name(),
                definition.application(),
                definition.versionNumber(),
                definition.digest().value(),
                workloadsFrom(definition.specification()));
    }

    private static List<WorkloadProfileResponse> workloadsFrom(
            PerformanceTestSpecification specification) {

        if (specification.spec() == null) {
            return List.of();
        }

        List<WorkloadProfileResponse> profiles = new ArrayList<>();
        for (PerformanceTestSpecification.WorkloadSpec workload : specification.spec().workloads()) {
            List<StageResponse> stages = workload.model() == null
                    ? List.of()
                    : workload.model().stages().stream()
                            .map(stage -> new StageResponse(
                                    stage.duration() == null ? 0 : stage.duration().toSeconds(),
                                    stage.targetUsers(), stage.targetRate()))
                            .toList();

            List<ScenarioResponse> scenarios = workload.scenarios().stream()
                    .map(scenario -> new ScenarioResponse(
                            scenario.name(), scenario.weight(), scenario.operationId()))
                    .toList();

            profiles.add(new WorkloadProfileResponse(
                    workload.name(),
                    workload.type() == null ? null : workload.type().name(),
                    workload.peakUsers(),
                    workload.iterationsPerUser(),
                    workload.model() == null || workload.model().type() == null
                            ? null : workload.model().type().name(),
                    workload.model() == null ? null : workload.model().maxConcurrency(),
                    workload.peakArrivalRate(),
                    workload.totalDuration().toSeconds(),
                    stages,
                    scenarios));
        }
        return List.copyOf(profiles);
    }

    private static ExecutionResponse executionFrom(RunReport.Execution execution) {
        return new ExecutionResponse(
                execution.engine(),
                execution.planDigest().value(),
                execution.shardCount(),
                execution.peakVirtualUsers(),
                execution.estimatedDurationSeconds());
    }

    private static MeasurementsResponse measurementsFrom(NormalizedRunResult result) {
        return new MeasurementsResponse(
                result.engine(),
                result.startedAt().orElse(null),
                result.completedAt().orElse(null),
                result.shardsExpected(),
                result.shardsReported(),
                result.isComplete(),
                OperationMetricsResponse.from(result.overall()),
                result.byOperation().values().stream()
                        .map(OperationMetricsResponse::from)
                        .toList());
    }
}
