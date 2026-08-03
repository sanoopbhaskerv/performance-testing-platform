package com.perfplatform.engine.jmeter;

import com.perfplatform.domain.execution.ProvisionRequest;
import com.perfplatform.domain.progress.LatencyHistogram;
import com.perfplatform.domain.progress.ProgressMetrics;
import com.perfplatform.domain.progress.ShardProgress;
import com.perfplatform.domain.results.OperationMetrics;
import com.perfplatform.domain.results.Sample;
import com.perfplatform.domain.results.SampleSet;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Reduces a JMeter results file that is still being written into a progress snapshot.
 *
 * <p>The local development provider's half of the live-monitoring contract. A Kubernetes runner does
 * this inside its own pod with a Python reducer, because it is the only process that can see its
 * results file; the local provider runs JMeter as a child process and has the file in hand, so it
 * does the same reduction here and publishes the same document. The document is what both agree on
 * ({@code ShardProgressJson}), not the code that produces it.
 *
 * <h2>Reading a file that is being appended to</h2>
 * The last line is routinely half-written. That costs nothing: {@code JtlParser} already counts a row
 * it cannot read rather than failing, and the row is complete by the next snapshot. What would cost
 * something is treating an unreadable file as an empty one — a run would report zero samples and the
 * live view would say it was measuring nothing, which is the one wrong answer this must never give.
 * A file that cannot be read at all therefore yields no snapshot rather than an empty one.
 */
final class JmeterProgressReducer {

    private JmeterProgressReducer() {
    }

    /**
     * What the previous snapshot reported, so throughput can be measured over a window.
     *
     * <p>Windowed rather than lifetime, because "how fast is it going" is a question about now: a
     * lifetime average hides a generator that stopped two minutes ago behind the traffic it sent
     * before it did.
     *
     * @param capturedAt  when the previous snapshot was taken, or the run's start for the first one
     * @param sampleCount its cumulative sample count, or zero
     */
    record Window(Instant capturedAt, long sampleCount) {
    }

    /**
     * Reduces what has been written so far.
     *
     * @param jtl the partial results file
     * @return empty when the file has no readable header yet — JMeter writes it before the first
     *         sample, so this is the first second or two of a run and not an error
     */
    static Optional<ShardProgress> reduce(ProvisionRequest request, String jtl, Instant capturedAt,
            Instant startedAt, Window previous) {

        SampleSet parsed;
        try {
            parsed = JtlParser.parse(jtl);
        } catch (JtlParser.UnreadableResultsException e) {
            return Optional.empty();
        }

        long windowSeconds = Math.max(1,
                Duration.between(previous.capturedAt(), capturedAt).toSeconds());
        long windowSamples = Math.max(0, parsed.samples().size() - previous.sampleCount());
        long throughputPerSecondMilli = (windowSamples * 1_000L) / windowSeconds;

        // Sorted, so two snapshots of the same run list their operations in the same order. A live
        // table whose rows reshuffled between polls would be unreadable for the one thing it is for.
        Map<String, List<Sample>> byOperation = new TreeMap<>();
        for (Sample sample : parsed.samples()) {
            byOperation.computeIfAbsent(sample.operation(), key -> new ArrayList<>()).add(sample);
        }

        Map<String, ProgressMetrics> operations = new LinkedHashMap<>();
        byOperation.forEach((operation, samples) -> operations.put(operation,
                // The whole run's throughput is not divided among operations: each carries its own
                // share, computed from the same window, so a per-operation rate is comparable with
                // the aggregate rather than a fraction of it.
                metrics(operation, samples, 0, share(throughputPerSecondMilli,
                        samples.size(), parsed.samples().size()))));

        ProgressMetrics overall = metrics(OperationMetrics.ALL, parsed.samples(),
                parsed.unparsedRows(), throughputPerSecondMilli);

        return Optional.of(new ShardProgress(
                request.runId(),
                request.shardId(),
                request.shardIndex(),
                request.shardCount(),
                request.engine(),
                request.planDigest().value(),
                capturedAt,
                Math.max(0, Duration.between(startedAt, capturedAt).toSeconds()),
                windowSeconds,
                activeUsers(jtl),
                overall,
                operations));
    }

    private static long share(long throughputPerSecondMilli, long part, long total) {
        return total == 0 ? 0 : (throughputPerSecondMilli * part) / total;
    }

    private static ProgressMetrics metrics(String name, List<Sample> samples, long unparsed,
            long throughputPerSecondMilli) {

        long errors = samples.stream().filter(sample -> !sample.success()).count();
        long min = Long.MAX_VALUE;
        long max = 0;
        long total = 0;
        long[] latencies = new long[samples.size()];

        for (int index = 0; index < samples.size(); index++) {
            long elapsed = samples.get(index).elapsedMillis();
            latencies[index] = elapsed;
            min = Math.min(min, elapsed);
            max = Math.max(max, elapsed);
            total += elapsed;
        }

        return new ProgressMetrics(name, samples.size(), errors, unparsed,
                samples.isEmpty() ? 0 : min, max,
                samples.isEmpty() ? 0 : total / samples.size(),
                throughputPerSecondMilli,
                LatencyHistogram.of(latencies));
    }

    /**
     * Threads active at the most recent sample, from JMeter's {@code allThreads} column.
     *
     * <p>Read from the file rather than taken from the plan's stage, because the two differ exactly
     * when it matters: a ramp that is behind schedule, a thread group that could not start, a
     * generator at its own limit. The plan says what was asked for; this says what is actually
     * applying load.
     *
     * @return 0 when the column is not being saved, which reads as "not reported" rather than as
     *         "no users"
     */
    private static long activeUsers(String jtl) {
        if (jtl == null || jtl.isBlank()) {
            return 0;
        }

        String[] lines = jtl.split("\r?\n", -1);
        if (lines.length < 2) {
            return 0;
        }

        int column = JtlParser.splitCsv(lines[0]).indexOf("allThreads");
        if (column < 0) {
            return 0;
        }

        // Backwards from the end, skipping the blank and half-written tail JMeter leaves while it
        // is appending.
        for (int index = lines.length - 1; index >= 1; index--) {
            if (lines[index].isBlank()) {
                continue;
            }
            List<String> fields = JtlParser.splitCsv(lines[index]);
            if (column >= fields.size()) {
                continue;
            }
            try {
                return Math.max(0, Long.parseLong(fields.get(column).trim()));
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }
}
