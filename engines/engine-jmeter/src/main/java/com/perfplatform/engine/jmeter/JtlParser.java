package com.perfplatform.engine.jmeter;

import com.perfplatform.domain.results.Sample;
import com.perfplatform.domain.results.SampleSet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads JMeter's CSV results format.
 *
 * <p>The only place in the platform that knows what a JTL column means. Everything downstream works
 * on {@link Sample}s, so adding an engine adds a parser and changes nothing else.
 *
 * <h2>Columns are located by header, not by position</h2>
 * JMeter's column set depends on its {@code jmeter.save.saveservice.*} configuration, so a parser
 * that indexed by position would read latency out of whichever column happened to be third. The
 * header row is required and is what the parser binds to.
 *
 * <h2>Unreadable rows are counted, never skipped</h2>
 * This is defect D5, fixed. V1 counted a request before parsing it, so a row it could not read
 * became a request that was neither a latency sample nor an error — the error rate improved the more
 * corrupt the file was. Here a row that cannot be read increments {@code unparsedRows}, which the
 * summarizer keeps in the error-rate denominator.
 *
 * <p>Written by hand rather than with a CSV library because the module's dependencies are restricted
 * to the domain and the SPI (CLAUDE.md §9), and because the subset needed is small and closed:
 * quoted fields, escaped quotes inside them, and nothing else.
 */
final class JtlParser {

    /** Columns this parser needs. A file missing any of them cannot be reduced. */
    private static final String TIMESTAMP = "timeStamp";
    private static final String ELAPSED = "elapsed";
    private static final String LABEL = "label";
    private static final String SUCCESS = "success";

    private JtlParser() {
    }

    /** Raised when a results file has no usable header. */
    static final class UnreadableResultsException extends RuntimeException {
        UnreadableResultsException(String message) {
            super(message);
        }
    }

    /**
     * Parses one shard's results file.
     *
     * @param jtl the file's content, including its header row
     */
    static SampleSet parse(String jtl) {
        if (jtl == null || jtl.isBlank()) {
            return SampleSet.empty();
        }

        String[] lines = jtl.split("\r?\n", -1);
        Map<String, Integer> columns = null;
        List<Sample> samples = new ArrayList<>();
        long unparsed = 0;

        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }

            List<String> fields = splitCsv(line);

            if (columns == null) {
                columns = headerIndex(fields);
                continue;
            }

            try {
                samples.add(toSample(fields, columns));
            } catch (RuntimeException e) {
                // Counted, not dropped, and not logged per row: a corrupt file can be millions of
                // rows and logging each one would be its own outage.
                unparsed++;
            }
        }

        if (columns == null) {
            throw new UnreadableResultsException(
                    "The results file has no header row, so its columns cannot be identified.");
        }

        return new SampleSet(samples, unparsed);
    }

    private static Map<String, Integer> headerIndex(List<String> header) {
        Map<String, Integer> columns = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            columns.put(header.get(i).trim(), i);
        }

        for (String required : List.of(TIMESTAMP, ELAPSED, LABEL, SUCCESS)) {
            if (!columns.containsKey(required)) {
                throw new UnreadableResultsException(
                        "The results file is missing the '" + required + "' column. Check the "
                                + "runner's saveservice configuration.");
            }
        }
        return columns;
    }

    private static Sample toSample(List<String> fields, Map<String, Integer> columns) {
        long timestamp = Long.parseLong(field(fields, columns, TIMESTAMP).trim());
        long elapsed = Long.parseLong(field(fields, columns, ELAPSED).trim());
        String label = field(fields, columns, LABEL);

        // Anything other than an explicit "true" is a failure. A row whose success column is blank
        // or unrecognized is not evidence of success, and defaulting it to true would report a
        // passing test built from rows nobody could interpret.
        boolean success = "true".equalsIgnoreCase(field(fields, columns, SUCCESS).trim());

        if (elapsed < 0) {
            throw new IllegalArgumentException("Negative elapsed time");
        }
        return new Sample(label.isBlank() ? "unlabelled" : label, elapsed, success, timestamp);
    }

    private static String field(List<String> fields, Map<String, Integer> columns, String name) {
        int index = columns.get(name);
        if (index >= fields.size()) {
            throw new IllegalArgumentException("Row is shorter than the header");
        }
        return fields.get(index);
    }

    /**
     * Splits a CSV line, honouring quoted fields.
     *
     * <p>Quoting matters here specifically: a scenario name becomes the {@code label} column, and a
     * name containing a comma would otherwise shift every column after it — silently reading the
     * wrong value as the latency.
     */
    static List<String> splitCsv(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);

            if (inQuotes) {
                if (c == '"') {
                    // A doubled quote inside a quoted field is a literal quote.
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    current.append(c);
                }
                continue;
            }

            switch (c) {
                case '"' -> inQuotes = true;
                case ',' -> {
                    fields.add(current.toString());
                    current.setLength(0);
                }
                default -> current.append(c);
            }
        }

        fields.add(current.toString());
        return fields;
    }
}
