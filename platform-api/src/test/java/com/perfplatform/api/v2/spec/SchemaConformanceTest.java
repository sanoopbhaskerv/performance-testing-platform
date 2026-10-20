package com.perfplatform.api.v2.spec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.perfplatform.domain.spec.LoadModelType;
import com.perfplatform.domain.spec.PerformanceTestSpecification;
import com.perfplatform.domain.spec.SourceType;
import com.perfplatform.domain.spec.TargetClass;
import com.perfplatform.domain.spec.WorkloadType;
import com.perfplatform.domain.validation.SecretValueDetector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps the published JSON Schema and the server-side validator from drifting apart.
 *
 * <p>The schema in {@code specification/schema/} and {@link SpecificationParser} plus
 * {@code SemanticValidator} encode overlapping rules. The schema is what consumers point their
 * editors and pipelines at; the server is what actually decides. When the two disagree, a
 * consumer's IDE says a specification is fine and the server rejects it — or worse, the reverse.
 *
 * <p>Rather than delegate validation to a JSON Schema library (a new dependency, and a
 * message-to-finding-code mapping that becomes its own drift surface), this pins the two together
 * at the three places drift actually happens:
 *
 * <ol>
 *   <li><b>Enums</b> — someone adds a {@code SourceType} in Java and forgets the schema.</li>
 *   <li><b>Required fields</b> — the schema says a field is required but nothing rejects a
 *       document without it.</li>
 *   <li><b>The secret-reference scheme list</b> — an approved secret manager is added to the
 *       detector but not to the pattern consumers validate against.</li>
 * </ol>
 *
 * <p>Deliberately <em>not</em> a full JSON Schema implementation. It checks correspondence, not
 * conformance: the schema remains documentation and tooling input, and the server remains
 * authoritative.
 */
class SchemaConformanceTest {

    /**
     * The engines a test deployment has.
     *
     * <p>Both, because the shipped examples use both and a validator that reported the browser
     * engine as unavailable would fail every browser fixture for the wrong reason.
     */
    private static final com.perfplatform.domain.port.EngineCatalog ENGINES =
            () -> java.util.Set.of("jmeter", "playwright");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private final SpecificationValidationService service =
            new SpecificationValidationService(new SpecificationParser(), ENGINES);

    private static JsonNode schema() {
        try {
            return JSON.readTree(ExampleFixtures.schema());
        } catch (Exception e) {
            throw new IllegalStateException("Published schema is not valid JSON", e);
        }
    }

    /** Enum values as authored in YAML: lowercase, hyphenated. */
    private static Set<String> authoredForm(Class<? extends Enum<?>> type) {
        return Stream.of(type.getEnumConstants())
                .map(value -> value.name().toLowerCase(Locale.ROOT).replace('_', '-'))
                .collect(Collectors.toSet());
    }

    private static Set<String> schemaEnum(String pointer) {
        JsonNode node = schema().at(pointer);
        assertThat(node.isArray())
                .as("schema pointer %s should be an enum array", pointer)
                .isTrue();
        Set<String> values = new java.util.LinkedHashSet<>();
        node.forEach(item -> values.add(item.asText()));
        return values;
    }

    // ── 1. Enums ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("enums")
    class Enums {

        @Test
        @DisplayName("targetClass matches TargetClass")
        void targetClass() {
            assertThat(schemaEnum("/$defs/environment/properties/targetClass/enum"))
                    .containsExactlyInAnyOrderElementsOf(authoredForm(TargetClass.class));
        }

        @Test
        @DisplayName("source type matches SourceType")
        void sourceType() {
            assertThat(schemaEnum("/$defs/source/properties/type/enum"))
                    .containsExactlyInAnyOrderElementsOf(authoredForm(SourceType.class));
        }

        @Test
        @DisplayName("workload type matches WorkloadType")
        void workloadType() {
            assertThat(schemaEnum("/$defs/workload/properties/type/enum"))
                    .containsExactlyInAnyOrderElementsOf(authoredForm(WorkloadType.class));
        }

        @Test
        @DisplayName("load model type matches LoadModelType")
        void loadModelType() {
            assertThat(schemaEnum("/$defs/loadModel/properties/type/enum"))
                    .containsExactlyInAnyOrderElementsOf(authoredForm(LoadModelType.class));
        }

        /**
         * k6 is technically suitable but is AGPL-3.0 licensed and has not been approved for
         * enterprise use. It must not become available by having quietly appeared in the schema.
         */
        @Test
        @DisplayName("no unapproved engine is offered by the schema")
        void engineListExcludesUnapprovedEngines() {
            Set<String> engines = schemaEnum("/$defs/workload/properties/engine/enum");

            assertThat(engines).containsExactlyInAnyOrder(
                    "jmeter", "playwright", "browserstack-playwright");
            assertThat(engines).doesNotContain("k6");
        }

        @Test
        @DisplayName("the document envelope matches the constants the validator enforces")
        void envelopeConstants() {
            assertThat(schema().at("/properties/apiVersion/const").asText())
                    .isEqualTo(PerformanceTestSpecification.SUPPORTED_API_VERSION);
            assertThat(schema().at("/properties/kind/const").asText())
                    .isEqualTo(PerformanceTestSpecification.SUPPORTED_KIND);
        }
    }

    // ── 2. Required fields ───────────────────────────────────────────────

    /**
     * Where a schema definition's {@code required} list is applied in a real document.
     *
     * @param fixture          a shipped valid example
     * @param documentPointer  the object in that example the definition describes
     * @param schemaPointer    the definition's {@code required} array
     */
    private record RequiredFieldSite(String fixture, String documentPointer, String schemaPointer) {
    }

    private static final List<RequiredFieldSite> REQUIRED_FIELD_SITES = List.of(
            new RequiredFieldSite("api-only.yaml", "", "/required"),
            new RequiredFieldSite("api-only.yaml", "/metadata", "/$defs/metadata/required"),
            new RequiredFieldSite("api-only.yaml", "/spec", "/$defs/spec/required"),
            new RequiredFieldSite("api-only.yaml", "/spec/environment", "/$defs/environment/required"),
            new RequiredFieldSite("api-only.yaml", "/spec/sources/0", "/$defs/source/required"),
            new RequiredFieldSite("api-only.yaml", "/spec/authentication/0",
                    "/$defs/authentication/required"),
            new RequiredFieldSite("api-only.yaml", "/spec/datasets/0", "/$defs/dataset/required"),
            new RequiredFieldSite("api-only.yaml", "/spec/datasets/0/source",
                    "/$defs/dataset/properties/source/required"),
            new RequiredFieldSite("api-only.yaml", "/spec/workloads/0", "/$defs/workload/required"),
            new RequiredFieldSite("api-only.yaml", "/spec/workloads/0/model",
                    "/$defs/loadModel/required"),
            new RequiredFieldSite("api-only.yaml", "/spec/workloads/0/model/stages/0",
                    "/$defs/loadModel/properties/stages/items/required"),
            new RequiredFieldSite("api-only.yaml", "/spec/workloads/0/scenarios/0",
                    "/$defs/scenario/required"),
            new RequiredFieldSite("api-only.yaml", "/spec/objectives/0", "/$defs/objective/required"),
            new RequiredFieldSite("hybrid.yaml", "/spec/workloads/1/startWhen",
                    "/$defs/workload/properties/startWhen/required"));

    /**
     * For every field the schema marks required, removes it from a valid example and asserts the
     * server rejects the result. A field the schema demands but the validator tolerates is a
     * silent divergence: the consumer's editor flags it, the platform accepts it.
     */
    @TestFactory
    @DisplayName("every field the schema requires is one the validator rejects when absent")
    Stream<DynamicTest> requiredFieldsAreEnforced() {
        JsonNode schema = schema();
        List<DynamicTest> tests = new ArrayList<>();

        for (RequiredFieldSite site : REQUIRED_FIELD_SITES) {
            JsonNode required = schema.at(site.schemaPointer());
            assertThat(required.isArray())
                    .as("schema pointer %s must be a required array", site.schemaPointer())
                    .isTrue();

            for (JsonNode field : required) {
                String fieldName = field.asText();
                String location = site.documentPointer().isEmpty()
                        ? "/" + fieldName
                        : site.documentPointer() + "/" + fieldName;

                tests.add(DynamicTest.dynamicTest(
                        "removing " + location + " from " + site.fixture() + " is rejected",
                        () -> assertRemovalIsRejected(site, fieldName, location)));
            }
        }
        return tests.stream();
    }

    private void assertRemovalIsRejected(RequiredFieldSite site, String fieldName, String location)
            throws Exception {
        JsonNode document = YAML.readTree(ExampleFixtures.valid(site.fixture()));

        JsonNode target = document.at(site.documentPointer());
        assertThat(target.isObject())
                .as("%s in %s should be an object", site.documentPointer(), site.fixture())
                .isTrue();
        assertThat(target.has(fieldName))
                .as("%s should be present in %s before removal", location, site.fixture())
                .isTrue();

        ((ObjectNode) target).remove(fieldName);

        var outcome = service.validate(JSON.writeValueAsString(document));

        assertThat(outcome.isValid())
                .as("removing required field %s left the specification valid. The schema demands "
                        + "it but nothing rejects its absence — a consumer's editor would flag "
                        + "this document while the platform accepted it.", location)
                .isFalse();
    }

    // ── 3. Secret reference schemes ──────────────────────────────────────

    @Nested
    @DisplayName("secret references")
    class SecretReferences {

        private static final Pattern ALTERNATION = Pattern.compile("\\^\\(([^)]+)\\)://");

        /**
         * Adding an approved secret manager to the detector without adding it to the pattern
         * would make every specification using it fail a consumer's editor while the platform
         * accepted it. Adding it to the pattern without the detector is worse: the editor would
         * bless a reference the platform then rejects as an inline value.
         */
        @Test
        @DisplayName("the schema pattern lists exactly the schemes the detector approves")
        void schemesMatch() {
            String pattern = schema().at("/$defs/secretRef/pattern").asText();
            Matcher matcher = ALTERNATION.matcher(pattern);

            assertThat(matcher.find())
                    .as("secretRef pattern %s should list schemes as an alternation group", pattern)
                    .isTrue();

            Set<String> fromSchema = Stream.of(matcher.group(1).split("\\|"))
                    .map(scheme -> scheme + "://")
                    .collect(Collectors.toSet());

            assertThat(fromSchema)
                    .containsExactlyInAnyOrderElementsOf(SecretValueDetector.approvedSchemes());
        }

        @Test
        @DisplayName("a reference the schema pattern accepts is accepted by the detector too")
        void patternAndDetectorAgree() {
            String pattern = schema().at("/$defs/secretRef/pattern").asText();

            for (String scheme : SecretValueDetector.approvedSchemes()) {
                String reference = scheme + "performance/app/credential";

                assertThat(Pattern.compile(pattern).matcher(reference).find())
                        .as("schema pattern should accept %s", reference)
                        .isTrue();
                assertThat(SecretValueDetector.check(reference, "/x").isValid())
                        .as("detector should accept %s", reference)
                        .isTrue();
            }
        }
    }
}
