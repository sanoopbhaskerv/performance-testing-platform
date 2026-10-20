package com.perfplatform.domain.progress;

import java.util.Map;
import java.util.StringJoiner;

/**
 * Writes the {@code progress.json} document a runner publishes.
 *
 * <p>The document is the contract between a runner and the control plane, and it has three
 * independent implementations by necessity: a Python reducer inside the JMeter runner image, a Node
 * one inside the Playwright image, and this one — used by the local development provider, which runs
 * JMeter as a child process and therefore has the results file in hand rather than in a pod.
 *
 * <p>Three implementations of one contract is a real cost, and it is paid deliberately. The
 * alternative is a runner that calls back into the control plane, which would mean giving a load
 * generator a credential that can write run state — and a provider able to write run state can
 * report its own failures as successes. The contract is small, versioned in the document itself, and
 * pinned by a decoder test on each producer's real output.
 *
 * <p>Hand-built rather than serialized by a library, for the same reason {@code
 * LocalJmeterExecutionProvider} hand-builds its runner manifest: the domain carries no JSON binding
 * (CLAUDE.md §9), and the shape here is fixed, flat and fully known. The only untrusted text is an
 * operation name, which a consumer authored, so that is the one thing escaped.
 */
public final class ShardProgressJson {

    private ShardProgressJson() {
    }

    /** The document, as a runner would publish it. */
    public static String write(ShardProgress progress) {
        StringBuilder json = new StringBuilder(512);
        json.append("{\"schema\":\"").append(ShardProgress.SCHEMA).append('"')
                .append(",\"runId\":\"").append(progress.runId().value()).append('"')
                .append(",\"shardId\":\"").append(progress.shardId().value()).append('"')
                .append(",\"shardIndex\":").append(progress.shardIndex())
                .append(",\"shardCount\":").append(progress.shardCount())
                .append(",\"engine\":\"").append(escape(progress.engine())).append('"')
                .append(",\"planDigest\":\"").append(escape(progress.planDigest())).append('"')
                .append(",\"capturedAt\":\"").append(progress.capturedAt()).append('"')
                .append(",\"elapsedSeconds\":").append(progress.elapsedSeconds())
                .append(",\"windowSeconds\":").append(progress.windowSeconds())
                .append(",\"activeUsers\":").append(progress.activeUsers())
                .append(",\"overall\":").append(metrics(progress.overall()))
                .append(",\"byOperation\":{");

        StringJoiner operations = new StringJoiner(",");
        for (Map.Entry<String, ProgressMetrics> entry : progress.byOperation().entrySet()) {
            operations.add("\"" + escape(entry.getKey()) + "\":" + metrics(entry.getValue()));
        }
        json.append(operations).append("}}");

        return json.toString();
    }

    private static String metrics(ProgressMetrics metrics) {
        return "{\"sampleCount\":" + metrics.sampleCount()
                + ",\"errorCount\":" + metrics.errorCount()
                + ",\"unparsedCount\":" + metrics.unparsedCount()
                + ",\"minMillis\":" + metrics.minMillis()
                + ",\"maxMillis\":" + metrics.maxMillis()
                + ",\"meanMillis\":" + metrics.meanMillis()
                + ",\"throughputPerSecondMilli\":" + metrics.throughputPerSecondMilli()
                // Both, always. A decoder that assumed the edges would misread a snapshot from an
                // older runner image and report confident, wrong latencies.
                + ",\"latencyBucketBoundsMillis\":" + longs(metrics.latency().boundsMillis())
                + ",\"latencyBucketCounts\":" + longs(metrics.latency().counts())
                + "}";
    }

    private static String longs(java.util.List<Long> values) {
        StringJoiner joiner = new StringJoiner(",", "[", "]");
        values.forEach(value -> joiner.add(String.valueOf(value)));
        return joiner.toString();
    }

    /**
     * JSON string escaping for the one field a consumer wrote.
     *
     * <p>An operation name is {@code workload/scenario}, and both halves come from a specification
     * somebody authored. A quote or a backslash in one would otherwise produce a document the
     * control plane cannot parse — which would present as a run whose live view mysteriously never
     * appears.
     */
    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
