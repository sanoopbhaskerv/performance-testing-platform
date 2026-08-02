package com.perfplatform.domain.dataset;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a dataset so that no two load generators send the same row.
 *
 * <p>This is the piece CLAUDE.md §15 requires of every shard — "non-overlapping dataset partition" —
 * and the reason shard count is still pinned at 1. Without it, raising the count does not raise the
 * load in any meaningful sense: ten generators reading the same file all submit the same order,
 * against the same account, in the same sequence. The target's caches answer most of it, the run
 * reports excellent latency, and the write path is exercised once while appearing to be exercised
 * ten times. That is the platform's recurring failure mode — a wrong answer rather than a failure.
 *
 * <h2>Round-robin, not contiguous blocks</h2>
 * Shard <i>i</i> takes rows <i>i</i>, <i>i+n</i>, <i>i+2n</i> … rather than a contiguous slice.
 * Datasets arrive sorted far more often than anyone intends — exported by created date, by customer,
 * by region — so contiguous blocks hand shard 0 every January record and shard 9 every December one.
 * The generators would then differ systematically from each other, and a per-shard latency
 * difference would say more about the data than about the system.
 *
 * <p>Round-robin also degrades sensibly: with fewer rows than shards, the later shards get nothing
 * rather than everyone getting a duplicate of row 1.
 *
 * <h2>Every partition keeps the header</h2>
 * A partition is a valid CSV in its own right, readable by an engine that knows nothing about
 * sharding. The header is what names the columns a scenario binds to, so a partition without one is
 * a file whose columns are positional — and a column order that changed between runs would silently
 * bind the wrong values.
 *
 * <h2>Deterministic</h2>
 * The same input and shard count always produce the same partitions, so a run can be reproduced from
 * its stored inputs. Nothing here consults a clock, a random source or a hash seed.
 */
public final class DatasetPartitioner {

    private DatasetPartitioner() {
    }

    /**
     * One shard's share of a dataset.
     *
     * @param shardIndex which shard this is for
     * @param header     the column names, repeated in every partition
     * @param rows       this shard's rows, in the order the source presented them
     */
    public record Partition(int shardIndex, String header, List<String> rows) {

        public Partition {
            rows = List.copyOf(rows);
        }

        /** The partition as a CSV document, header first. */
        public String toCsv() {
            StringBuilder csv = new StringBuilder(header);
            for (String row : rows) {
                csv.append('\n').append(row);
            }
            return csv.toString();
        }

        /** Whether this shard received no rows at all — fewer rows than shards. */
        public boolean isEmpty() {
            return rows.isEmpty();
        }
    }

    /**
     * Splits a CSV document into non-overlapping partitions, one per shard.
     *
     * @param csv        the whole dataset, header row first
     * @param shardCount how many generators will read it
     * @return one partition per shard, in shard-index order
     * @throws IllegalArgumentException if the document has no header or the shard count is not
     *                                  positive. Both are programming errors rather than user input:
     *                                  the document is validated on upload and the shard count comes
     *                                  from the execution plan
     */
    public static List<Partition> partition(String csv, int shardCount) {
        if (shardCount < 1) {
            throw new IllegalArgumentException("shardCount must be at least 1, was " + shardCount);
        }

        List<String> lines = readLines(csv);
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("A dataset must have a header row");
        }

        String header = lines.get(0);
        List<List<String>> buckets = new ArrayList<>(shardCount);
        for (int shard = 0; shard < shardCount; shard++) {
            buckets.add(new ArrayList<>());
        }

        for (int row = 1; row < lines.size(); row++) {
            buckets.get((row - 1) % shardCount).add(lines.get(row));
        }

        List<Partition> partitions = new ArrayList<>(shardCount);
        for (int shard = 0; shard < shardCount; shard++) {
            partitions.add(new Partition(shard, header, buckets.get(shard)));
        }
        return partitions;
    }

    /**
     * The column names the file actually has.
     *
     * <p>Distinct from the columns a specification <em>declares</em>, and deliberately so. The
     * declaration is what validation checks a scenario's {@code ${...}} references against, because
     * validation is side-effect-free and must work before any file exists. This is what the file
     * turned out to contain. Comparing the two before a run is what stops a re-exported dataset from
     * silently binding different values to the same names — the same contract-and-verification
     * shape as the plan digest, not a second source of truth.
     */
    public static List<String> columnsOf(String csv) {
        List<String> lines = readLines(csv);
        if (lines.isEmpty()) {
            return List.of();
        }
        List<String> columns = new ArrayList<>();
        for (String column : lines.get(0).split(",", -1)) {
            columns.add(column.trim());
        }
        return List.copyOf(columns);
    }

    /**
     * The document as lines, with blank lines and a trailing newline discarded.
     *
     * <p>Handles all three line endings: a dataset exported from a spreadsheet on Windows arrives
     * with {@code \r\n}, and a stray {@code \r} left on a value would be sent as part of it.
     */
    private static List<String> readLines(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (String line : csv.split("\r\n|\r|\n", -1)) {
            if (!line.isBlank()) {
                lines.add(line);
            }
        }
        return lines;
    }
}
