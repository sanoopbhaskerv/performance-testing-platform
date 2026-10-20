package com.perfplatform.characterization;

import com.perfplatform.config.TeamConfig;
import com.perfplatform.engine.JmeterTestOrchestrator;
import freemarker.template.Configuration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Characterization tests for JMX generation in {@link JmeterTestOrchestrator}.
 *
 * <p>These pin the current Freemarker template output so the JMeter plan compiler can be
 * moved behind the engine SPI (CLAUDE.md §14, §15) without silently altering generated plans.
 * Several tests below assert defects that CLAUDE.md §15 requires correcting in Increment 1D;
 * they are marked KNOWN DEFECT and must be inverted — not deleted — when the compiler is fixed.
 *
 * <p>Only the template-rendering half of the orchestrator is exercised. The
 * {@code ProcessBuilder} half is intentionally not invoked: no JMeter binary is required
 * and no subprocess is started.
 */
class JmxGenerationCharacterizationTest {

    private final JmeterTestOrchestrator orchestrator = new JmeterTestOrchestrator(freemarkerConfiguration());

    private static Configuration freemarkerConfiguration() {
        Configuration cfg = new Configuration(Configuration.VERSION_2_3_32);
        cfg.setClassLoaderForTemplateLoading(JmxGenerationCharacterizationTest.class.getClassLoader(), "templates");
        cfg.setDefaultEncoding("UTF-8");
        return cfg;
    }

    /**
     * {@code generateJmx} is private in V1. Increment 0 must not change production code,
     * so the characterization reaches it reflectively rather than widening its visibility.
     */
    private String generateJmx(Path runDir, TeamConfig config, String testType) throws Throwable {
        Method method = JmeterTestOrchestrator.class
                .getDeclaredMethod("generateJmx", Path.class, TeamConfig.class, String.class);
        method.setAccessible(true);
        try {
            Path jmx = (Path) method.invoke(orchestrator, runDir, config, testType);
            return Files.readString(jmx);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private TeamConfig config() {
        TeamConfig config = new TeamConfig();
        config.setTeam("payments");

        TeamConfig.TargetConfig target = new TeamConfig.TargetConfig();
        target.setName("payment-service");
        target.setBaseUrl("http://payment-service:8080");
        config.setTargets(List.of(target));

        config.getTests().getLoad().setEnabled(true);
        config.getTests().getLoad().setRampUpUsers(25);
        config.getTests().getLoad().setSteadyStateUsers(100);
        config.getTests().getLoad().setRampUpDurationSeconds(30);
        config.getTests().getLoad().setSteadyStateDurationSeconds(300);
        config.getTests().getLoad().setScenarios(List.of(
                scenario("browse", endpoint("GET", "/api/users", null)),
                scenario("checkout", endpoint("POST", "/api/payments", "{\"amount\":100.0}"))));

        config.getTests().getEndurance().setUsers(20);
        config.getTests().getEndurance().setDurationMinutes(60);
        return config;
    }

    private TeamConfig.ScenarioConfig scenario(String name, TeamConfig.EndpointConfig... endpoints) {
        TeamConfig.ScenarioConfig scenario = new TeamConfig.ScenarioConfig();
        scenario.setName(name);
        scenario.setEndpoints(List.of(endpoints));
        return scenario;
    }

    private TeamConfig.EndpointConfig endpoint(String method, String path, String body) {
        TeamConfig.EndpointConfig endpoint = new TeamConfig.EndpointConfig();
        endpoint.setMethod(method);
        endpoint.setPath(path);
        endpoint.setBody(body);
        return endpoint;
    }

    @Test
    @DisplayName("load test renders a valid test plan carrying the team name and thread settings")
    void rendersLoadTestPlan(@TempDir Path dir) throws Throwable {
        String jmx = generateJmx(dir, config(), "load");

        assertThat(jmx).startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        assertThat(jmx).contains("<jmeterTestPlan");
        assertThat(jmx).contains("testname=\"payments Load Test\"");
        assertThat(jmx).contains("<stringProp name=\"ThreadGroup.num_threads\">100</stringProp>");
        assertThat(jmx).contains("<stringProp name=\"ThreadGroup.ramp_time\">30</stringProp>");
        assertThat(jmx).contains("<stringProp name=\"ThreadGroup.duration\">300</stringProp>");
    }

    @Test
    @DisplayName("the target base URL becomes the HTTP Defaults domain with the scheme stripped")
    void rendersTargetDomain(@TempDir Path dir) throws Throwable {
        String jmx = generateJmx(dir, config(), "load");

        assertThat(jmx).contains("<stringProp name=\"HTTPSampler.domain\">payment-service:8080</stringProp>");
    }

    @Test
    @DisplayName("each configured endpoint renders one HTTP sampler with its method, path and body")
    void rendersOneSamplerPerEndpoint(@TempDir Path dir) throws Throwable {
        String jmx = generateJmx(dir, config(), "load");

        assertThat(jmx).contains("testname=\"GET /api/users\"");
        assertThat(jmx).contains("testname=\"POST /api/payments\"");
        assertThat(jmx).contains("<stringProp name=\"HTTPSampler.method\">GET</stringProp>");
        assertThat(jmx).contains("<stringProp name=\"HTTPSampler.method\">POST</stringProp>");
        assertThat(jmx).contains("{\"amount\":100.0}");
        // postBodyRaw is true only for the endpoint that carries a body.
        assertThat(countOccurrences(jmx, "<boolProp name=\"HTTPSampler.postBodyRaw\">true</boolProp>")).isEqualTo(1);
        assertThat(countOccurrences(jmx, "<boolProp name=\"HTTPSampler.postBodyRaw\">false</boolProp>")).isEqualTo(1);
    }

    /**
     * KNOWN DEFECT (CLAUDE.md §5 "Flattening all scenarios into one endpoint list", §15).
     * Every scenario's endpoints are concatenated into a single flat list, so scenario
     * boundaries and weights are lost: two scenarios produce two samplers in one thread
     * group, and neither scenario name appears anywhere in the generated plan.
     * Increment 1D must invert this assertion.
     */
    @Test
    @DisplayName("KNOWN DEFECT: scenarios are flattened into one endpoint list, losing names and weights")
    void flattensScenariosLosingBoundaries(@TempDir Path dir) throws Throwable {
        String jmx = generateJmx(dir, config(), "load");

        assertThat(jmx).doesNotContain("browse");
        assertThat(jmx).doesNotContain("checkout");
        assertThat(countOccurrences(jmx, "<ThreadGroup ")).isEqualTo(1);
        assertThat(countOccurrences(jmx, "<HTTPSamplerProxy ")).isEqualTo(2);
    }

    /**
     * KNOWN DEFECT (CLAUDE.md §5, §15). {@code buildTemplateModel} always reads
     * {@code tests.load.scenarios} regardless of the requested test type, so an endurance
     * run silently executes the load test's endpoints. Increment 1D must select scenarios
     * per workload.
     */
    @Test
    @DisplayName("KNOWN DEFECT: endurance runs reuse tests.load.scenarios")
    void enduranceReusesLoadScenarios(@TempDir Path dir) throws Throwable {
        TeamConfig config = config();
        // Endurance defines no scenarios of its own; the load scenarios leak into its plan.
        String jmx = generateJmx(dir, config, "endurance");

        assertThat(jmx).contains("testname=\"GET /api/users\"");
        assertThat(jmx).contains("testname=\"POST /api/payments\"");
        assertThat(jmx).contains("<stringProp name=\"ThreadGroup.num_threads\">20</stringProp>");
    }

    /**
     * KNOWN DEFECT — MATERIAL. Freemarker applies locale-aware digit grouping to numeric
     * models, so any value of 1000 or more is rendered with a thousands separator. The
     * generated JMX then carries {@code 3,600} where JMeter expects {@code 3600}, producing an
     * invalid test plan.
     *
     * <p>This is not a cosmetic issue. It corrupts:
     * <ul>
     *   <li>{@code ThreadGroup.duration} for any endurance run of 17 minutes or more
     *       (60 minutes is the documented default);</li>
     *   <li>{@code ThreadGroup.num_threads} at exactly the 1,000 virtual-user ceiling this
     *       platform is designed for (CLAUDE.md §3);</li>
     *   <li>{@code ThreadGroup.ramp_time} and spike peak users at the same threshold.</li>
     * </ul>
     *
     * <p>The template must apply {@code ?c} (computer number format) to every numeric
     * interpolation, or the compiler must pass pre-formatted strings. Increment 1D must fix
     * this and invert the assertions below.
     */
    @Test
    @DisplayName("KNOWN DEFECT: values >= 1000 render with a thousands separator, corrupting the plan")
    void numberFormattingCorruptsValuesAtOrAboveOneThousand(@TempDir Path dir) throws Throwable {
        TeamConfig config = config();
        // 60 minutes -> 3600 seconds, the documented endurance default.
        String enduranceJmx = generateJmx(dir, config, "endurance");

        assertThat(enduranceJmx).contains("<stringProp name=\"ThreadGroup.duration\">3,600</stringProp>");
        assertThat(enduranceJmx).doesNotContain("<stringProp name=\"ThreadGroup.duration\">3600</stringProp>");

        // The same corruption at the platform's maximum supported virtual-user count.
        config.getTests().getLoad().setSteadyStateUsers(1000);
        config.getTests().getLoad().setSteadyStateDurationSeconds(1800);
        String loadJmx = generateJmx(dir, config, "load");

        assertThat(loadJmx).contains("<stringProp name=\"ThreadGroup.num_threads\">1,000</stringProp>");
        assertThat(loadJmx).contains("<stringProp name=\"ThreadGroup.duration\">1,800</stringProp>");
    }

    /**
     * KNOWN DEFECT: {@code generateJmx} selects {@code spike-test.jmx.ftl}, but that template
     * does not exist in {@code src/main/resources/templates/jmx/}. A spike run therefore fails
     * at render time. The V1 API reaches this only when {@code tests.spike.enabled=true}.
     */
    @Test
    @DisplayName("KNOWN DEFECT: spike test type fails because spike-test.jmx.ftl does not exist")
    void spikeTemplateIsMissing(@TempDir Path dir) {
        assertThatThrownBy(() -> generateJmx(dir, config(), "spike"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("spike-test.jmx.ftl");
    }

    @Test
    @DisplayName("an unrecognized test type falls back to the load template")
    void unknownTestTypeFallsBackToLoad(@TempDir Path dir) throws Throwable {
        String jmx = generateJmx(dir, config(), "something-else");

        assertThat(jmx).contains("testname=\"payments Load Test\"");
    }

    /**
     * KNOWN GAP: no correlation header is injected into generated plans. CLAUDE.md §15 and §22
     * require every workload to carry a run correlation header. Increment 1D must invert this.
     */
    @Test
    @DisplayName("KNOWN GAP: generated plans carry no run correlation header")
    void generatesNoCorrelationHeader(@TempDir Path dir) throws Throwable {
        String jmx = generateJmx(dir, config(), "load");

        assertThat(jmx).doesNotContain("X-Performance-Test-Run-Id");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = haystack.indexOf(needle);
        while (index >= 0) {
            count++;
            index = haystack.indexOf(needle, index + needle.length());
        }
        return count;
    }
}
