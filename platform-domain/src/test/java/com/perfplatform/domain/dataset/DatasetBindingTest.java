package com.perfplatform.domain.dataset;

import com.perfplatform.domain.spec.PerformanceTestSpecification.ScenarioSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Finding the dataset columns a journey binds to.
 *
 * <p>What matters here is that nothing is missed and nothing extra is claimed. A missed reference
 * passes validation and is then sent as literal text, so every request fails and the run reports the
 * latency of an error path. A reference invented from text that was never a reference produces a
 * blocking error on a test that was fine.
 */
class DatasetBindingTest {

    private static ScenarioSpec scenario(String path, Map<String, String> query,
            Map<String, String> headers, String body) {
        return new ScenarioSpec("journey", 100, null, "POST", path, headers, query,
                body == null ? null : new ScenarioSpec.RequestBody("application/json", body));
    }

    @Nested
    @DisplayName("finding references")
    class Finding {

        @Test
        @DisplayName("every part of a request is searched")
        void searchesTheWholeRequest() {
            ScenarioSpec spec = scenario(
                    "/orders/${orderId}",
                    Map.of("region", "${region}"),
                    Map.of("X-Tenant", "${tenantId}"),
                    "{\"sku\":\"${sku}\"}");

            assertThat(DatasetBinding.referencesIn(spec))
                    .containsExactlyInAnyOrder("orderId", "region", "tenantId", "sku");
        }

        @Test
        @DisplayName("several references in one value are all found")
        void findsSeveralInOneValue() {
            assertThat(DatasetBinding.referencesIn(
                    scenario("/${tenant}/orders/${orderId}", Map.of(), Map.of(), null)))
                    .containsExactly("tenant", "orderId");
        }

        @Test
        @DisplayName("a repeated reference is reported once")
        void deduplicates() {
            assertThat(DatasetBinding.referencesIn(
                    scenario("/${id}/related/${id}", Map.of(), Map.of(), null)))
                    .containsExactly("id");
        }

        @Test
        @DisplayName("a request with no references reports none")
        void findsNothingWhenThereIsNothing() {
            assertThat(DatasetBinding.referencesIn(
                    scenario("/orders", Map.of("status", "open"), Map.of(), "{}"))).isEmpty();
        }

        @Test
        @DisplayName("a parameter name can carry a reference as well as its value")
        void searchesKeysAsWellAsValues() {
            assertThat(DatasetBinding.referencesIn(Map.of("${columnName}", "fixed")))
                    .containsExactly("columnName");
        }

        @Test
        @DisplayName("a scenario with no path, headers or body does not fail")
        void toleratesAnEmptyScenario() {
            assertThat(DatasetBinding.referencesIn(new ScenarioSpec("j", 100, "opId"))).isEmpty();
        }
    }

    @Nested
    @DisplayName("what is not a reference")
    class NotReferences {

        /**
         * JMeter's syntax also covers function calls. They are deliberately not matched: a
         * specification is not a place to smuggle in engine functions the platform never validated,
         * and treating one as a column name would produce a confusing error about a missing column.
         */
        @Test
        @DisplayName("a JMeter function call is not a column reference")
        void ignoresEngineFunctions() {
            assertThat(DatasetBinding.referencesIn(
                    scenario("/orders", Map.of("n", "${__threadNum}"), Map.of(),
                            "{\"r\":\"${__Random(1,9)}\"}")))
                    .isEmpty();
        }

        @Test
        @DisplayName("a bare dollar or an unclosed brace is not a reference")
        void ignoresMalformedText() {
            assertThat(DatasetBinding.referencesIn(
                    scenario("/price", Map.of("cost", "$100"), Map.of("X-A", "${unclosed"), "$")))
                    .isEmpty();
        }

        @Test
        @DisplayName("a name starting with a digit is not a reference")
        void ignoresNamesThatCannotBeColumns() {
            assertThat(DatasetBinding.referencesIn(
                    scenario("/x", Map.of("a", "${1st}"), Map.of(), null))).isEmpty();
        }

        @Test
        @DisplayName("hasReference answers the same question for a single value")
        void reportsWhetherAnyReferenceExists() {
            assertThat(DatasetBinding.hasReference("/orders/${id}")).isTrue();
            assertThat(DatasetBinding.hasReference("/orders")).isFalse();
            assertThat(DatasetBinding.hasReference(null)).isFalse();
        }
    }
}
