package com.perfplatform.engine.jmeter;

import com.perfplatform.domain.identity.TestRunId;
import com.perfplatform.domain.results.SampleSet;
import com.perfplatform.engine.spi.RunnerOutput;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The contract between the runner image and the engine that reads its output.
 *
 * <p>The fixture is real: it was produced by {@code runner-images/jmeter-runner} executing a
 * platform-generated plan against the bundled demo service, and trimmed to a representative slice.
 * Nothing else guarantees the two agree — the image is built and versioned separately from this
 * code, so a change to its {@code jmeter.save.saveservice.*} flags would silently produce results
 * the platform cannot read, and the failure would look like a run that measured nothing.
 *
 * <p>The columns asserted here are the ones the platform actually depends on. If this test fails
 * after an image change, the image is wrong until proven otherwise: the parser's expectations are
 * the older contract.
 */
class RunnerOutputContractTest {

    private static final String FIXTURE = "/runner-output/jmeter-runner-results.jtl";

    private static String realRunnerOutput() {
        try (var in = RunnerOutputContractTest.class.getResourceAsStream(FIXTURE)) {
            if (in == null) {
                throw new IllegalStateException("Missing runner output fixture " + FIXTURE);
            }
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static SampleSet parsed() {
        return new JmeterEngineProvider().parse(new RunnerOutput(
                TestRunId.generate(), 1,
                List.of(new RunnerOutput.ShardResult(0, realRunnerOutput()))));
    }

    @Test
    @DisplayName("output written by the runner image parses, with nothing unreadable")
    void runnerOutputParses() {
        SampleSet samples = parsed();

        assertThat(samples.samples()).isNotEmpty();
        // Unparsed rows are kept in the error-rate denominator, so any here would quietly worsen
        // every run's reported error rate rather than failing loudly.
        assertThat(samples.unparsedRows())
                .as("a row the parser cannot read is counted against the run, not ignored")
                .isZero();
    }

    /**
     * Objectives are scoped by workload and by scenario, so results labelled any other way could not
     * be judged. The runner is told these labels; this asserts it wrote them back unchanged.
     */
    @Test
    @DisplayName("samples carry workload/scenario labels, so objectives can be resolved")
    void labelsSurviveTheRoundTrip() {
        assertThat(parsed().samples())
                .extracting(sample -> sample.operation())
                .contains("demo-load/browse-users");
    }

    /**
     * The demo service fails about 5% of payments on purpose, so a fixture taken from a real run
     * contains genuine failures. A parser that read every row as successful would report a perfect
     * error rate for a system that was failing.
     */
    @Test
    @DisplayName("failed requests are read as failures, not as successes")
    void failuresAreRecognised() {
        assertThat(parsed().samples())
                .anyMatch(sample -> !sample.success())
                .anyMatch(sample -> sample.success());
    }

    @Test
    @DisplayName("elapsed time and timestamp are read, so latency and throughput are computable")
    void timingsAreRead() {
        assertThat(parsed().samples())
                .allSatisfy(sample -> {
                    assertThat(sample.elapsedMillis()).isNotNegative();
                    assertThat(sample.timestampMillis()).isPositive();
                });
    }

    /**
     * The header the image's save-service flags produce. Asserted literally because it is the
     * contract: the parser reads by column name, and a reordered or renamed column is exactly the
     * kind of change that would otherwise be discovered in production.
     */
    @Test
    @DisplayName("the image writes the column set the parser reads by name")
    void headerCarriesTheExpectedColumns() {
        String header = realRunnerOutput().lines().findFirst().orElseThrow();

        assertThat(header.split(","))
                .contains("timeStamp", "elapsed", "label", "responseCode", "success", "Latency");
    }

    @Test
    @DisplayName("the fixture is genuinely runner-produced, not hand-written")
    void fixtureIsReal() {
        // Written by the demo service through the runner image. A hand-made fixture would agree with
        // the parser by construction and prove nothing about the image.
        assertThat(realRunnerOutput())
                .contains("http://perf-demo:8081/api/")
                .contains("demo-load/submit-payment");
    }

    @Test
    @DisplayName("the runner image and its entrypoint are part of the repository")
    void imageIsVersionedAlongsideTheCode() {
        Path root = repositoryRoot();

        assertThat(root.resolve("runner-images/jmeter-runner/Dockerfile")).exists();
        assertThat(root.resolve("runner-images/jmeter-runner/entrypoint.sh")).exists();
    }

    private static Path repositoryRoot() {
        Path candidate = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (candidate != null) {
            if (Files.isDirectory(candidate.resolve("runner-images"))
                    && Files.isRegularFile(candidate.resolve("pom.xml"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("Could not locate the repository root");
    }
}
