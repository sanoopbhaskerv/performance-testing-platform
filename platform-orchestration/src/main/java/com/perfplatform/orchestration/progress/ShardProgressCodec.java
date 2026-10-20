package com.perfplatform.orchestration.progress;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.perfplatform.domain.identity.RunnerShardId;
import com.perfplatform.domain.identity.TestRunId;
import com.perfplatform.domain.progress.LatencyHistogram;
import com.perfplatform.domain.progress.ProgressMetrics;
import com.perfplatform.domain.progress.ShardProgress;
import com.perfplatform.domain.results.OperationMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads the {@code progress.json} a runner published.
 *
 * <p>This parses a file written by an image the control plane does not control the release of. Three
 * consequences shape it:
 *
 * <ul>
 *   <li><b>A malformed snapshot is discarded, never fatal.</b> Live progress is a convenience. A run
 *       whose reconciliation pass threw because a runner wrote a truncated file — which happens
 *       naturally, since the object is being overwritten while it is being read — would be a run
 *       that could not reach a terminal state, and that is a far worse outcome than a chart that
 *       skips a point.</li>
 *   <li><b>Bucket edges are read, never assumed.</b> An older image may use different ones, and a
 *       decoder that assumed the current set would reassign every sample to the wrong bucket and
 *       report confident, wrong latencies.</li>
 *   <li><b>Identity is checked by the caller, not here.</b> This turns bytes into a value; whether
 *       that value describes the run being reconciled is a decision with consequences, and it
 *       belongs where those consequences are handled.</li>
 * </ul>
 */
public final class ShardProgressCodec {

    private static final Logger log = LoggerFactory.getLogger(ShardProgressCodec.class);

    /** Bounded so a corrupt or hostile object cannot be parsed into the control plane's heap. */
    static final int MAX_DOCUMENT_BYTES = 512 * 1024;

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Decodes one snapshot.
     *
     * @return empty when the document cannot be read as a snapshot at all. The reason is logged at
     *         debug, not warn: a partially written object is the expected case, not an incident
     */
    public Optional<ShardProgress> decode(byte[] content) {
        if (content == null || content.length == 0 || content.length > MAX_DOCUMENT_BYTES) {
            return Optional.empty();
        }

        try {
            JsonNode root = mapper.readTree(new String(content, StandardCharsets.UTF_8));
            if (!root.isObject()) {
                return Optional.empty();
            }

            String schema = root.path("schema").asText("");
            if (!ShardProgress.SCHEMA.equals(schema)) {
                // Named rather than guessed. A document whose shape this decoder does not know is
                // more likely a newer runner than a corrupt file, and reading it as though it were
                // the shape it knows is how fields get silently misinterpreted.
                log.debug("Ignoring a progress snapshot declaring schema '{}'", schema);
                return Optional.empty();
            }

            Map<String, ProgressMetrics> byOperation = new LinkedHashMap<>();
            JsonNode operations = root.path("byOperation");
            operations.fieldNames().forEachRemaining(name ->
                    metrics(name, operations.path(name)).ifPresent(m -> byOperation.put(name, m)));

            Optional<ProgressMetrics> overall =
                    metrics(OperationMetrics.ALL, root.path("overall"));
            if (overall.isEmpty()) {
                return Optional.empty();
            }

            return Optional.of(new ShardProgress(
                    TestRunId.of(root.path("runId").asText()),
                    RunnerShardId.of(root.path("shardId").asText()),
                    root.path("shardIndex").asInt(0),
                    Math.max(1, root.path("shardCount").asInt(1)),
                    root.path("engine").asText("unknown"),
                    root.path("planDigest").asText(""),
                    Instant.parse(root.path("capturedAt").asText()),
                    Math.max(0, root.path("elapsedSeconds").asLong(0)),
                    Math.max(0, root.path("windowSeconds").asLong(0)),
                    Math.max(0, root.path("activeUsers").asLong(0)),
                    overall.get(),
                    byOperation));

        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            log.debug("A progress snapshot could not be read; it will be skipped", e);
            return Optional.empty();
        }
    }

    private Optional<ProgressMetrics> metrics(String name, JsonNode node) {
        if (node == null || !node.isObject()) {
            return Optional.empty();
        }

        List<Long> bounds = longs(node.path("latencyBucketBoundsMillis"));
        List<Long> counts = longs(node.path("latencyBucketCounts"));
        if (bounds.isEmpty() || counts.size() != bounds.size() + 1) {
            return Optional.empty();
        }

        long samples = Math.max(0, node.path("sampleCount").asLong(0));
        long errors = Math.max(0, node.path("errorCount").asLong(0));

        try {
            return Optional.of(new ProgressMetrics(
                    name,
                    samples,
                    // Clamped rather than refused. A runner that reported more errors than samples
                    // has a counting bug; dropping the whole snapshot over it would lose the
                    // latency distribution too, which is still perfectly readable.
                    Math.min(errors, samples),
                    Math.max(0, node.path("unparsedCount").asLong(0)),
                    Math.max(0, node.path("minMillis").asLong(0)),
                    Math.max(0, node.path("maxMillis").asLong(0)),
                    Math.max(0, node.path("meanMillis").asLong(0)),
                    Math.max(0, node.path("throughputPerSecondMilli").asLong(0)),
                    new LatencyHistogram(bounds, counts)));
        } catch (IllegalArgumentException e) {
            log.debug("A progress snapshot's metrics for '{}' were not usable", name, e);
            return Optional.empty();
        }
    }

    private static List<Long> longs(JsonNode array) {
        if (array == null || !array.isArray()) {
            return List.of();
        }
        List<Long> values = new ArrayList<>(array.size());
        array.forEach(element -> values.add(element.asLong(0)));
        return values;
    }
}
