package com.perfplatform.domain.results;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything a run measured, before it is summarized.
 *
 * <p>What an engine produces from its own output format, and the only thing it produces. Percentile
 * arithmetic and objective evaluation happen afterwards, once, in {@code platform-results} — so two
 * engines' results are summarized identically and remain comparable.
 *
 * <h2>Unreadable rows are counted, not dropped</h2>
 * {@link #unparsedRows()} exists because of defect D5. V1 incremented its request total before
 * parsing a row, so a row it could not read counted as a request that was neither a latency sample
 * nor an error — quietly improving the error rate the more corrupt the results were. Counting them
 * separately means the denominator is honest and the corruption is visible.
 *
 * @param samples      readable measurements, in the order the engine emitted them
 * @param unparsedRows rows that could not be read at all
 */
public record SampleSet(List<Sample> samples, long unparsedRows) {

    public SampleSet {
        samples = samples == null ? List.of() : List.copyOf(samples);
        if (unparsedRows < 0) {
            throw new IllegalArgumentException("unparsedRows must not be negative");
        }
    }

    public static SampleSet empty() {
        return new SampleSet(List.of(), 0);
    }

    public boolean isEmpty() {
        return samples.isEmpty();
    }

    /** The two sets combined, for merging one shard's samples into another's. */
    public SampleSet merge(SampleSet other) {
        if (other == null) {
            return this;
        }
        List<Sample> combined = new ArrayList<>(samples.size() + other.samples.size());
        combined.addAll(samples);
        combined.addAll(other.samples);
        return new SampleSet(combined, unparsedRows + other.unparsedRows);
    }
}
