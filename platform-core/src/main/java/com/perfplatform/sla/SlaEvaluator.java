package com.perfplatform.sla;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.perfplatform.config.TeamConfig.SlaConfig;
import com.perfplatform.results.metrics.PercentileCalculator;

/**
 * Reads JMeter results CSV and evaluates SLA thresholds.
 *
 * JMeter results CSV columns: timeStamp, elapsed, label, responseCode,
 * responseMessage, threadName, dataType, success, failureMessage,
 * bytes, sentBytes, grpThreads, allThreads, URL, Latency, IdleTime, Connect
 *
 * <p>V1 component. Percentile computation has moved to
 * {@link PercentileCalculator} in {@code platform-results} so that V2 objective evaluation and
 * V1 SLA evaluation cannot drift apart. The rest of this class — CSV parsing and threshold
 * comparison — is replaced by the normalized result model in Increment 1D.
 */
@Service
public class SlaEvaluator {

    private static final Logger log = LoggerFactory.getLogger(SlaEvaluator.class);

    public record SlaResult(
            long p50LatencyMs,
            long p95LatencyMs,
            long p99LatencyMs,
            double errorRatePercent,
            long totalRequests,
            long totalErrors,
            boolean passed,
            String failureReason) {
    }

    public SlaResult evaluate(Path resultsFile, SlaConfig config) {
        if (resultsFile == null || !Files.exists(resultsFile)) {
            log.warn("JMeter results file not found: {}. Cannot evaluate SLA.", resultsFile);
            return new SlaResult(0, 0, 0, 0, 0, 0, false, "Results file not found");
        }

        List<Long> latencies = new ArrayList<>();
        long totalRequests = 0;
        long totalErrors = 0;

        try (Reader reader = new FileReader(resultsFile.toFile())) {
            Iterable<CSVRecord> records = CSVFormat.DEFAULT
                    .withFirstRecordAsHeader()
                    .parse(reader);

            for (CSVRecord record : records) {
                totalRequests++;
                try {
                    long elapsed = Long.parseLong(record.get("elapsed"));
                    latencies.add(elapsed);
                    boolean success = "true".equalsIgnoreCase(record.get("success"));
                    if (!success)
                        totalErrors++;
                } catch (IllegalArgumentException e) {
                    log.debug("Skipping malformed CSV row: {}", record);
                }
            }
        } catch (IOException e) {
            log.error("Failed to read JMeter results CSV", e);
            return new SlaResult(0, 0, 0, 0, 0, 0, false, "Failed to read results: " + e.getMessage());
        }

        if (latencies.isEmpty()) {
            return new SlaResult(0, 0, 0, 0, 0, 0, false, "No results in CSV");
        }

        Collections.sort(latencies);
        long p50 = PercentileCalculator.fromSorted(latencies, 50);
        long p95 = PercentileCalculator.fromSorted(latencies, 95);
        long p99 = PercentileCalculator.fromSorted(latencies, 99);
        double errorRate = totalRequests > 0 ? (totalErrors * 100.0 / totalRequests) : 0;

        log.info("SLA Metrics — P50: {}ms, P95: {}ms, P99: {}ms, Errors: {}%",
                p50, p95, p99, String.format("%.2f", errorRate));

        // Evaluate against SLA thresholds
        List<String> breaches = new ArrayList<>();
        if (p95 > config.getMaxP95LatencyMs()) {
            breaches.add(String.format("P95 latency %dms > threshold %dms", p95, config.getMaxP95LatencyMs()));
        }
        if (errorRate > config.getMaxErrorRatePercent()) {
            breaches.add(
                    String.format("Error rate %.2f%% > threshold %.2f%%", errorRate, config.getMaxErrorRatePercent()));
        }

        boolean passed = breaches.isEmpty();
        String failureReason = passed ? null : String.join("; ", breaches);

        return new SlaResult(p50, p95, p99, errorRate, totalRequests, totalErrors, passed, failureReason);
    }
}
