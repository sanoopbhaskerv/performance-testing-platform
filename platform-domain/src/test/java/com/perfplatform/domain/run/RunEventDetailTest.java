package com.perfplatform.domain.run;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The event-detail codec.
 *
 * <p>Hand-written because the domain has no serialization library, which is only safe if the format
 * is genuinely closed. These tests are what establish that: the shapes it accepts, the shapes it
 * refuses, and that anything written can be read back unchanged.
 */
class RunEventDetailTest {

    @Test
    @DisplayName("an empty or null map renders as an empty object")
    void emptyRendersAsEmptyObject() {
        assertThat(RunEventDetail.toJson(null)).isEqualTo("{}");
        assertThat(RunEventDetail.toJson(Map.of())).isEqualTo("{}");
        assertThat(RunEventDetail.fromJson("{}")).isEmpty();
        assertThat(RunEventDetail.fromJson(null)).isEmpty();
        assertThat(RunEventDetail.fromJson("  ")).isEmpty();
    }

    /**
     * Operators diff these values. A map whose iteration order shifted between JVM runs would show
     * phantom changes in an audit trail.
     */
    @Test
    @DisplayName("keys are emitted in sorted order, whatever order they were added in")
    void keysAreSorted() {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("shardIndex", "0");
        detail.put("owner", "reconciler-1");
        detail.put("engine", "jmeter");

        assertThat(RunEventDetail.toJson(detail))
                .isEqualTo("{\"engine\":\"jmeter\",\"owner\":\"reconciler-1\",\"shardIndex\":\"0\"}");
    }

    @Test
    @DisplayName("a detail map round-trips unchanged")
    void roundTrips() {
        Map<String, String> detail = Map.of(
                "reason", "Admitted by capacity policy",
                "owner", "reconciler-9f2a",
                "shardIndex", "3");

        assertThat(RunEventDetail.fromJson(RunEventDetail.toJson(detail))).isEqualTo(detail);
    }

    /**
     * A reason containing a quote or a newline would otherwise emit something that is not JSON, and
     * the failure would surface much later as an unreadable event.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "a \"quoted\" value",
            "back\\slash",
            "line\nbreak",
            "carriage\rreturn",
            "tab\tseparated",
            "unicode: \u00e9\u00fc",
            "control\u0001character",
            "{\"nested\": \"json\"}"})
    @DisplayName("values needing escaping survive the round trip")
    void escapingRoundTrips(String awkward) {
        Map<String, String> detail = Map.of("reason", awkward);

        assertThat(RunEventDetail.fromJson(RunEventDetail.toJson(detail)))
                .containsEntry("reason", awkward);
    }

    @Test
    @DisplayName("a key needing escaping survives too")
    void escapedKeyRoundTrips() {
        Map<String, String> detail = Map.of("odd\"key", "value");

        assertThat(RunEventDetail.fromJson(RunEventDetail.toJson(detail)))
                .containsEntry("odd\"key", "value");
    }

    @Test
    @DisplayName("a null value is stored as an empty string rather than the text 'null'")
    void nullValueBecomesEmpty() {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("reason", null);

        assertThat(RunEventDetail.toJson(detail)).isEqualTo("{\"reason\":\"\"}");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not json",
            "{",
            "{\"key\"}",
            "{\"key\":}",
            "{\"key\":\"value\"",
            "{\"key\":\"value\"} trailing",
            "{\"key\":\"unterminated}",
            "{\"key\":\"bad escape \\q\"}",
            "{\"key\":\"truncated unicode \\u12\"}"})
    @DisplayName("anything outside the supported shape is rejected, not silently accepted")
    void rejectsMalformed(String malformed) {
        assertThatThrownBy(() -> RunEventDetail.fromJson(malformed))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Nested objects and non-string values are outside what event detail is allowed to carry, and
     * accepting them would quietly widen the format the storage column is shaped around.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "{\"key\":{\"nested\":\"value\"}}",
            "{\"key\":123}",
            "{\"key\":true}",
            "{\"key\":null}",
            "[\"array\"]"})
    @DisplayName("nested and non-string values are refused")
    void refusesRicherJson(String richer) {
        assertThatThrownBy(() -> RunEventDetail.fromJson(richer))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("whitespace between tokens is tolerated")
    void toleratesWhitespace() {
        assertThat(RunEventDetail.fromJson("  { \"a\" : \"1\" , \"b\" : \"2\" }  "))
                .containsEntry("a", "1")
                .containsEntry("b", "2");
    }
}
