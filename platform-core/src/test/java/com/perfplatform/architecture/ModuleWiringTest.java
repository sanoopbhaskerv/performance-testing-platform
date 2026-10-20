package com.perfplatform.architecture;

import com.perfplatform.domain.identity.TestRunId;
import com.perfplatform.engine.jmeter.JmeterEngineProvider;
import com.perfplatform.engine.playwright.PlaywrightEngineProvider;
import com.perfplatform.engine.spi.ExecutionEngineProvider;
import com.perfplatform.engine.spi.WorkloadKind;
import com.perfplatform.results.metrics.PercentileCalculator;
import com.perfplatform.sla.SlaEvaluator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the modular skeleton is really wired: types from each extracted module are reachable
 * from the composition root at compile time and at runtime, and an engine is usable through the
 * SPI rather than through its concrete type.
 *
 * <p>Without this, a module could sit in the reactor with nothing depending on it and the
 * skeleton would be scaffolding rather than structure.
 */
class ModuleWiringTest {

    @Test
    @DisplayName("platform-domain types are reachable from the composition root")
    void domainIsOnTheClasspath() {
        TestRunId runId = TestRunId.generate();

        assertThat(runId.value()).isNotNull();
        assertThat(TestRunId.of(runId.toString())).isEqualTo(runId);
    }

    @Test
    @DisplayName("engines are usable through the SPI without referencing their concrete types downstream")
    void enginesAreReachableThroughTheSpi() {
        ExecutionEngineProvider jmeter = new JmeterEngineProvider();
        ExecutionEngineProvider playwright = new PlaywrightEngineProvider();

        assertThat(jmeter.engineId()).isEqualTo("jmeter");
        assertThat(jmeter.capabilities().supports(WorkloadKind.PROTOCOL)).isTrue();
        assertThat(playwright.engineId()).isEqualTo("playwright");
        assertThat(playwright.capabilities().supports(WorkloadKind.BROWSER)).isTrue();
    }

    @Test
    @DisplayName("platform-results types are reachable from the composition root")
    void resultsIsOnTheClasspath() {
        assertThat(PercentileCalculator.fromSorted(List.of(10L, 20L, 30L, 40L), 50)).isEqualTo(20L);
    }

    /**
     * The one behavior move in Increment 1A. V1's {@code SlaEvaluator} now delegates percentile
     * computation to {@code platform-results}; this asserts the delegation actually happens by
     * checking that the evaluator's output matches the extracted calculator on the same samples.
     * {@code SlaEvaluatorCharacterizationTest} independently pins that the values are unchanged
     * from the baseline.
     */
    @Test
    @DisplayName("V1 SLA evaluation and the extracted calculator agree on the same samples")
    void slaEvaluatorDelegatesToTheExtractedCalculator(@TempDir Path dir) throws IOException {
        StringBuilder csv = new StringBuilder("timeStamp,elapsed,label,responseCode,success\n");
        List<Long> samples = List.of(120L, 340L, 55L, 890L, 210L, 430L, 77L, 660L, 150L, 990L);
        for (Long sample : samples) {
            csv.append("1704070800000,").append(sample).append(",GET /x,200,true\n");
        }
        Path results = dir.resolve("results.csv");
        Files.writeString(results, csv.toString());

        SlaEvaluator.SlaResult evaluated =
                new SlaEvaluator().evaluate(results, new com.perfplatform.config.TeamConfig.SlaConfig());

        List<Long> sorted = samples.stream().sorted().toList();
        assertThat(evaluated.p50LatencyMs()).isEqualTo(PercentileCalculator.fromSorted(sorted, 50));
        assertThat(evaluated.p95LatencyMs()).isEqualTo(PercentileCalculator.fromSorted(sorted, 95));
        assertThat(evaluated.p99LatencyMs()).isEqualTo(PercentileCalculator.fromSorted(sorted, 99));
    }
}
