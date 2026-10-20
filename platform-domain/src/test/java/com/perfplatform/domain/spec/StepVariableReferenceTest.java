package com.perfplatform.domain.spec;

import com.perfplatform.domain.spec.PerformanceTestSpecification.ScenarioSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.StepSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@link StepSpec#referencedVariables()} sees.
 *
 * <p>This is the question "does this correlation feed the journey, or does the journey still send
 * the literal the rule was meant to replace" — so a location that goes unscanned is a rule the
 * Validator would wrongly call dead, and one that is over-eagerly matched is a dead rule it would
 * wrongly wave through.
 */
class StepVariableReferenceTest {

    @Test
    @DisplayName("every place a step can carry a value is scanned")
    void findsVariablesInEveryLocation() {
        StepSpec step = new StepSpec("order", "POST", "/orders/${orderId}",
                Map.of("Authorization", "Bearer ${token}"),
                Map.of("page", "${page}"),
                new ScenarioSpec.RequestBody("application/json", "{\"sku\":\"${sku}\"}"),
                List.of(), List.of(), null);

        assertThat(step.referencedVariables())
                .containsExactlyInAnyOrder("orderId", "token", "page", "sku");
    }

    @Test
    @DisplayName("a step carrying only literals references nothing")
    void findsNothingWhenEveryValueIsLiteral() {
        StepSpec step = new StepSpec("order", "POST", "/orders/42",
                Map.of("Authorization", "Bearer abc123"),
                Map.of("page", "1"),
                new ScenarioSpec.RequestBody("application/json", "{\"sku\":\"demo\"}"),
                List.of(), List.of(), null);

        assertThat(step.referencedVariables()).isEmpty();
    }

    @Test
    @DisplayName("a bare dollar or an unclosed brace is not a reference")
    void ignoresTextThatOnlyLooksLikeAReference() {
        StepSpec step = new StepSpec("prices", "GET", "/prices?under=$100",
                Map.of("X-Note", "${unterminated"), Map.of(), null,
                List.of(), List.of(), null);

        assertThat(step.referencedVariables()).isEmpty();
    }

    @Test
    @DisplayName("one variable used twice is reported once")
    void deduplicates() {
        StepSpec step = new StepSpec("order", "GET", "/orders/${id}",
                Map.of(), Map.of("ref", "${id}"), null, List.of(), List.of(), null);

        assertThat(step.referencedVariables()).containsExactly("id");
    }

    @Test
    @DisplayName("a step with no body or path does not fail")
    void toleratesAbsentParts() {
        StepSpec step = new StepSpec("ping", "GET", null, Map.of(), Map.of(), null,
                List.of(), List.of(), null);

        assertThat(step.referencedVariables()).isEmpty();
    }
}
