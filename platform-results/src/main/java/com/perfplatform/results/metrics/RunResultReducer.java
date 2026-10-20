package com.perfplatform.results.metrics;

import com.perfplatform.domain.identity.TestRunId;
import com.perfplatform.domain.results.NormalizedRunResult;
import com.perfplatform.domain.results.OperationMetrics;
import com.perfplatform.domain.results.Sample;
import com.perfplatform.domain.results.SampleSet;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Summarizes samples into the measurements objectives are judged against.
 *
 * <p>The single summarizer for every engine. An engine parses its own output into
 * {@link SampleSet}; everything after that happens here, so a p95 means the same thing whether the
 * samples came from JMeter, a browser journey, or an engine that does not exist yet. Two runs of the
 * same test on different engines remain comparable, which they would not be if each engine did its
 * own arithmetic.
 *
 * <p>Deterministic and re-runnable. Given the same samples the same numbers come out, which is what
 * makes a disputed pass or fail settleable by recomputation from stored artifacts rather than by
 * argument. Nothing here reads a clock or any configuration.
 */
public final class RunResultReducer {

    private RunResultReducer() {
    }

    /**
     * Reduces one run's samples.
     *
     * @param runId          the run being summarized
     * @param engine         which engine produced the samples
     * @param shardsExpected how many shards the plan called for
     * @param shardsReported how many produced usable output; fewer means the result is incomplete
     *                       and objective evaluation will refuse to pass it
     */
    public static NormalizedRunResult reduce(TestRunId runId, String engine, int shardsExpected,
            int shardsReported, SampleSet sampleSet) {

        List<Sample> samples = sampleSet.samples();
        if (samples.isEmpty()) {
            // Still carries the shard counts: "nothing was measured" and "two of four shards
            // reported nothing" are different situations and lead to different investigations.
            return new NormalizedRunResult(runId, engine, Optional.empty(), Optional.empty(),
                    shardsExpected, shardsReported,
                    withUnparsed(OperationMetrics.empty(OperationMetrics.ALL), sampleSet.unparsedRows()),
                    Map.of());
        }

        long windowStart = Long.MAX_VALUE;
        long windowEnd = Long.MIN_VALUE;
        // Secondary measurements can have different units (for example layout-shift scores).
        // They remain individually queryable without changing headline journey/request results.
        List<Sample> aggregateSamples = samples.stream().filter(Sample::includeInAggregate).toList();

        // Grouped by operation in a sorted map so the output order is stable across runs. An order
        // that depended on hash iteration would make two summaries of identical samples differ.
        Map<String, List<Sample>> byOperation = new TreeMap<>();
        for (Sample sample : samples) {
            byOperation.computeIfAbsent(sample.operation(), key -> new ArrayList<>()).add(sample);
            if (sample.includeInAggregate()) {
                windowStart = Math.min(windowStart, sample.timestampMillis());
                windowEnd = Math.max(windowEnd, sample.timestampMillis() + sample.elapsedMillis());
            }
        }

        if (aggregateSamples.isEmpty()) {
            windowStart = samples.stream().mapToLong(Sample::timestampMillis).min().orElseThrow();
            windowEnd = samples.stream().mapToLong(Sample::timestampMillis).max().orElseThrow();
        }

        long windowMillis = Math.max(1, windowEnd - windowStart);

        Map<String, OperationMetrics> operations = new LinkedHashMap<>();
        byOperation.forEach((operation, operationSamples) ->
                operations.put(operation, summarize(operation, operationSamples, windowMillis, 0)));

        // Unparsed rows are attributed to the aggregate only. They cannot be attributed to an
        // operation — being unreadable is precisely why their operation is unknown.
        OperationMetrics overall = aggregateSamples.isEmpty()
                ? withUnparsed(OperationMetrics.empty(OperationMetrics.ALL), sampleSet.unparsedRows())
                : summarize(OperationMetrics.ALL, aggregateSamples, windowMillis, sampleSet.unparsedRows());

        return new NormalizedRunResult(runId, engine,
                Optional.of(Instant.ofEpochMilli(windowStart)),
                Optional.of(Instant.ofEpochMilli(windowEnd)),
                shardsExpected, shardsReported, overall, operations);
    }

    private static OperationMetrics summarize(String name, List<Sample> samples, long windowMillis,
            long unparsedRows) {

        List<Long> latencies = new ArrayList<>(samples.size());
        long errors = 0;
        long total = 0;
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;

        for (Sample sample : samples) {
            long elapsed = sample.elapsedMillis();
            latencies.add(elapsed);
            total += elapsed;
            min = Math.min(min, elapsed);
            max = Math.max(max, elapsed);
            if (!sample.success()) {
                errors++;
            }
        }

        Collections.sort(latencies);
        long count = latencies.size();

        // Throughput scaled by 1,000 so it stays an exact integer. A double here would make a
        // throughput objective's verdict depend on floating-point accumulation order.
        long throughputPerSecondMilli = (count * 1_000L * 1_000L) / windowMillis;

        return new OperationMetrics(
                name,
                count,
                errors,
                unparsedRows,
                min,
                max,
                total / count,
                PercentileCalculator.fromSorted(latencies, 50),
                PercentileCalculator.fromSorted(latencies, 90),
                PercentileCalculator.fromSorted(latencies, 95),
                PercentileCalculator.fromSorted(latencies, 99),
                throughputPerSecondMilli,
                PercentileCalculator.fromSorted(latencies, 75));
    }

    private static OperationMetrics withUnparsed(OperationMetrics metrics, long unparsedRows) {
        return new OperationMetrics(metrics.name(), metrics.sampleCount(), metrics.errorCount(),
                unparsedRows, metrics.minMillis(), metrics.maxMillis(), metrics.meanMillis(),
                metrics.p50Millis(), metrics.p90Millis(), metrics.p95Millis(), metrics.p99Millis(),
                metrics.throughputPerSecondMilli(), metrics.p75Millis());
    }
}
