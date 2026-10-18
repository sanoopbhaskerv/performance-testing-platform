package com.perfplatform.engine.playwright;

import com.perfplatform.domain.results.Sample;
import com.perfplatform.domain.results.SampleSet;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads the browser runner's compact result CSV.
 *
 * <p>Columns: {@code metric,operation,value,success,timestamp}. Values are integers in the
 * threshold unit the platform compares: milliseconds for durations, milli-score for CLS, percent
 * for Lighthouse score. Web-vital rows are labelled as {@code metric@operation} so the common
 * reducer can keep separate metrics without adding engine-specific fields to the persisted result.
 */
final class PlaywrightResultParser {

    private static final String WEB_LCP = "web.lcp";
    private static final String WEB_INP = "web.inp";
    private static final String WEB_CLS = "web.cls";
    private static final String WEB_TTFB = "web.ttfb";
    private static final String WEB_TBT = "web.tbt";
    private static final String WEB_LIGHTHOUSE_SCORE = "web.lighthouse.score";

    private PlaywrightResultParser() {
    }

    static SampleSet parse(String csv) {
        if (csv == null || csv.isBlank()) {
            return SampleSet.empty();
        }

        List<Sample> samples = new ArrayList<>();
        long unparsed = 0;
        String[] lines = csv.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            if (i == 0 && line.toLowerCase(java.util.Locale.ROOT).startsWith("metric,")) {
                continue;
            }

            String[] columns = line.split(",", -1);
            if (columns.length != 5) {
                unparsed++;
                continue;
            }
            try {
                String metric = columns[0].trim();
                String operation = columns[1].trim();
                long value = Long.parseLong(columns[2].trim());
                boolean success = Boolean.parseBoolean(columns[3].trim());
                long timestamp = Long.parseLong(columns[4].trim());
                if (metric.isEmpty() || operation.isEmpty()) {
                    unparsed++;
                    continue;
                }
                samples.add(new Sample(operationKey(metric, operation), value, success, timestamp,
                        "browser.journey.duration".equals(metric)));
            } catch (RuntimeException e) {
                unparsed++;
            }
        }
        return new SampleSet(samples, unparsed);
    }

    private static String operationKey(String metric, String operation) {
        return isWebMetric(metric) ? metric + "@" + operation : operation;
    }

    private static boolean isWebMetric(String metric) {
        return WEB_LCP.equals(metric)
                || WEB_INP.equals(metric)
                || WEB_CLS.equals(metric)
                || WEB_TTFB.equals(metric)
                || WEB_TBT.equals(metric)
                || WEB_LIGHTHOUSE_SCORE.equals(metric);
    }
}
