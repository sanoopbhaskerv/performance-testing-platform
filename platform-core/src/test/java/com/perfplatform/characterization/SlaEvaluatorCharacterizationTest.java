package com.perfplatform.characterization;

import com.perfplatform.config.TeamConfig.SlaConfig;
import com.perfplatform.sla.SlaEvaluator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Characterization tests for {@link SlaEvaluator}.
 *
 * <p>These tests pin the <em>current</em> V1 behavior so that the percentile and
 * error-rate logic can be moved behind the engine-neutral normalized result model
 * (CLAUDE.md §18) without silently changing results. They deliberately assert what
 * the code does today, including the rough edges documented below.
 */
class SlaEvaluatorCharacterizationTest {

    private static final String HEADER = "timeStamp,elapsed,label,responseCode,success\n";

    private final SlaEvaluator evaluator = new SlaEvaluator();

    private SlaConfig sla(int maxP95Ms, double maxErrorPercent) {
        SlaConfig config = new SlaConfig();
        config.setMaxP95LatencyMs(maxP95Ms);
        config.setMaxErrorRatePercent(maxErrorPercent);
        return config;
    }

    private Path csv(Path dir, String body) throws IOException {
        Path file = dir.resolve("results.csv");
        Files.writeString(file, HEADER + body);
        return file;
    }

    @Test
    @DisplayName("percentiles use ceil(p/100 * n) - 1 index into the ascending latency list")
    void computesPercentilesFromSortedLatencies(@TempDir Path dir) throws IOException {
        // 10 samples: 10..100ms. p50 -> index 4 (50), p95 -> index 9 (100), p99 -> index 9 (100).
        StringBuilder body = new StringBuilder();
        for (int i = 1; i <= 10; i++) {
            body.append("1704070800000,").append(i * 10).append(",GET /x,200,true\n");
        }

        SlaEvaluator.SlaResult result = evaluator.evaluate(csv(dir, body.toString()), sla(1000, 1.0));

        assertThat(result.p50LatencyMs()).isEqualTo(50L);
        assertThat(result.p95LatencyMs()).isEqualTo(100L);
        assertThat(result.p99LatencyMs()).isEqualTo(100L);
        assertThat(result.totalRequests()).isEqualTo(10L);
        assertThat(result.totalErrors()).isZero();
        assertThat(result.errorRatePercent()).isZero();
        assertThat(result.passed()).isTrue();
        assertThat(result.failureReason()).isNull();
    }

    @Test
    @DisplayName("latencies are sorted before percentile selection, so input order does not matter")
    void sortsBeforeSelectingPercentile(@TempDir Path dir) throws IOException {
        String body = """
                1704070800000,900,GET /x,200,true
                1704070800000,100,GET /x,200,true
                1704070800000,500,GET /x,200,true
                1704070800000,300,GET /x,200,true
                """;

        SlaEvaluator.SlaResult result = evaluator.evaluate(csv(dir, body), sla(1000, 1.0));

        assertThat(result.p50LatencyMs()).isEqualTo(300L);
        assertThat(result.p95LatencyMs()).isEqualTo(900L);
    }

    @Test
    @DisplayName("error rate counts success=false rows and is expressed as a percentage")
    void computesErrorRate(@TempDir Path dir) throws IOException {
        String body = """
                1704070800000,100,GET /x,200,true
                1704070800000,100,GET /x,500,false
                1704070800000,100,GET /x,200,true
                1704070800000,100,GET /x,503,false
                """;

        SlaEvaluator.SlaResult result = evaluator.evaluate(csv(dir, body), sla(1000, 1.0));

        assertThat(result.totalRequests()).isEqualTo(4L);
        assertThat(result.totalErrors()).isEqualTo(2L);
        assertThat(result.errorRatePercent()).isEqualTo(50.0);
    }

    @Test
    @DisplayName("success flag comparison is case-insensitive")
    void treatsSuccessFlagCaseInsensitively(@TempDir Path dir) throws IOException {
        String body = """
                1704070800000,100,GET /x,200,TRUE
                1704070800000,100,GET /x,200,True
                """;

        SlaEvaluator.SlaResult result = evaluator.evaluate(csv(dir, body), sla(1000, 1.0));

        assertThat(result.totalErrors()).isZero();
    }

    @Test
    @DisplayName("p95 above threshold is reported as a breach with a descriptive reason")
    void breachesOnP95(@TempDir Path dir) throws IOException {
        String body = """
                1704070800000,900,GET /x,200,true
                1704070800000,900,GET /x,200,true
                """;

        SlaEvaluator.SlaResult result = evaluator.evaluate(csv(dir, body), sla(500, 1.0));

        assertThat(result.passed()).isFalse();
        assertThat(result.failureReason()).isEqualTo("P95 latency 900ms > threshold 500ms");
    }

    @Test
    @DisplayName("error rate above threshold is reported as a breach")
    void breachesOnErrorRate(@TempDir Path dir) throws IOException {
        String body = """
                1704070800000,100,GET /x,200,true
                1704070800000,100,GET /x,500,false
                """;

        SlaEvaluator.SlaResult result = evaluator.evaluate(csv(dir, body), sla(1000, 1.0));

        assertThat(result.passed()).isFalse();
        assertThat(result.failureReason()).isEqualTo("Error rate 50.00% > threshold 1.00%");
    }

    @Test
    @DisplayName("multiple breaches are joined with '; ' in a single failure reason")
    void joinsMultipleBreaches(@TempDir Path dir) throws IOException {
        String body = """
                1704070800000,900,GET /x,200,true
                1704070800000,900,GET /x,500,false
                """;

        SlaEvaluator.SlaResult result = evaluator.evaluate(csv(dir, body), sla(500, 1.0));

        assertThat(result.failureReason())
                .isEqualTo("P95 latency 900ms > threshold 500ms; Error rate 50.00% > threshold 1.00%");
    }

    @Test
    @DisplayName("thresholds are exclusive: a value exactly on the limit passes")
    void thresholdIsExclusive(@TempDir Path dir) throws IOException {
        String body = "1704070800000,500,GET /x,200,true\n";

        SlaEvaluator.SlaResult result = evaluator.evaluate(csv(dir, body), sla(500, 1.0));

        assertThat(result.passed()).isTrue();
    }

    @Test
    @DisplayName("a missing results file yields a non-passing zeroed result, not an exception")
    void handlesMissingResultsFile(@TempDir Path dir) {
        SlaEvaluator.SlaResult result = evaluator.evaluate(dir.resolve("absent.csv"), sla(1000, 1.0));

        assertThat(result.passed()).isFalse();
        assertThat(result.failureReason()).isEqualTo("Results file not found");
        assertThat(result.totalRequests()).isZero();
    }

    @Test
    @DisplayName("a null results path yields a non-passing zeroed result, not an exception")
    void handlesNullResultsFile() {
        SlaEvaluator.SlaResult result = evaluator.evaluate(null, sla(1000, 1.0));

        assertThat(result.passed()).isFalse();
        assertThat(result.failureReason()).isEqualTo("Results file not found");
    }

    @Test
    @DisplayName("a header-only CSV yields 'No results in CSV' and does not pass")
    void handlesEmptyResultSet(@TempDir Path dir) throws IOException {
        SlaEvaluator.SlaResult result = evaluator.evaluate(csv(dir, ""), sla(1000, 1.0));

        assertThat(result.passed()).isFalse();
        assertThat(result.failureReason()).isEqualTo("No results in CSV");
    }

    /**
     * KNOWN V1 BEHAVIOR: {@code totalRequests} is incremented before the row is parsed,
     * so a row with an unparseable {@code elapsed} value still counts as a request but
     * contributes no latency sample. This skews the error rate denominator. Pinned here
     * so the replacement normalized result model (CLAUDE.md §18) makes the change explicit.
     */
    @Test
    @DisplayName("KNOWN GAP: malformed rows still count toward totalRequests but not latency")
    void malformedRowsCountAsRequestsButNotSamples(@TempDir Path dir) throws IOException {
        String body = """
                1704070800000,100,GET /x,200,true
                1704070800000,not-a-number,GET /x,200,true
                """;

        SlaEvaluator.SlaResult result = evaluator.evaluate(csv(dir, body), sla(1000, 1.0));

        assertThat(result.totalRequests()).isEqualTo(2L);
        assertThat(result.p95LatencyMs()).isEqualTo(100L);
        assertThat(result.totalErrors()).isZero();
    }
}
