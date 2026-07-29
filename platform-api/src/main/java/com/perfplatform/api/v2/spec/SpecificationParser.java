package com.perfplatform.api.v2.spec;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.perfplatform.domain.spec.HumanDuration;
import com.perfplatform.domain.spec.LoadModelType;
import com.perfplatform.domain.spec.PerformanceTestSpecification;
import com.perfplatform.domain.spec.PerformanceTestSpecification.AuthenticationSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.DatasetSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.EnvironmentSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.GovernanceSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.LoadModel;
import com.perfplatform.domain.spec.PerformanceTestSpecification.LoadStage;
import com.perfplatform.domain.spec.PerformanceTestSpecification.ObjectiveSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.ObservabilitySpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.ScenarioSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.SourceSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.SpecMetadata;
import com.perfplatform.domain.spec.PerformanceTestSpecification.StartCondition;
import com.perfplatform.domain.spec.PerformanceTestSpecification.TestSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.WorkloadSpec;
import com.perfplatform.domain.spec.SourceType;
import com.perfplatform.domain.spec.TargetClass;
import com.perfplatform.domain.spec.Threshold;
import com.perfplatform.domain.spec.WorkloadType;
import com.perfplatform.domain.validation.ValidationFinding;
import com.perfplatform.domain.validation.ValidationResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Maps a submitted YAML or JSON document onto the canonical domain model.
 *
 * <p>This is structural validation — the layer that answers "is this document well formed" before
 * {@code SemanticValidator} answers "does this test make sense". It is written as an explicit
 * traversal rather than Jackson data binding for three reasons: the domain records stay free of
 * serialization annotations, every finding carries an exact JSON-pointer path instead of a
 * framework exception message, and an unknown field is reported as a finding rather than either
 * silently dropped or turned into a 500.
 *
 * <p>Unknown fields are rejected, matching {@code additionalProperties: false} in the published
 * schema. Silently ignoring a misspelled field is worse than failing: a consumer who writes
 * {@code targetUser} instead of {@code targetUsers} would otherwise get a test that runs at a
 * load they never chose.
 *
 * <p>Parsing never throws on bad input. Malformed documents produce findings, because this feeds
 * a validation endpoint whose job is to explain problems, not to fail.
 */
public final class SpecificationParser {

    /** YAML is a superset of JSON, so one factory accepts both submitted forms. */
    private final ObjectMapper mapper = new ObjectMapper(new YAMLFactory());

    /** Writes the stored form. Separate factory because the YAML one cannot emit JSON. */
    private final ObjectMapper jsonMapper = new ObjectMapper();

    /**
     * Converts a submitted YAML or JSON document to the JSON text that gets stored.
     *
     * <p>Storage is JSON regardless of what was submitted, because the column is a JSON type and
     * because YAML and JSON are two serializations of one model — keeping both would mean two
     * stored forms of the same specification.
     *
     * <p>Field order and content are preserved exactly as submitted. Nothing is normalized here:
     * the digest is taken over the canonical form of the <em>parsed model</em>, so re-ordering the
     * text would change what is stored without changing what it means, and a consumer comparing
     * their file to what the platform holds would see a diff that is not a difference.
     *
     * @throws IllegalArgumentException if the document cannot be read at all
     */
    public String toStoredJson(String document) {
        if (document == null || document.isBlank()) {
            throw new IllegalArgumentException("The submitted specification is empty");
        }
        try {
            return jsonMapper.writeValueAsString(mapper.readTree(document));
        } catch (JsonProcessingException e) {
            // The parser message can quote request content, so it is not propagated.
            throw new IllegalArgumentException("The submitted specification could not be read");
        }
    }

    /**
     * The outcome of parsing.
     *
     * @param specification present when the document was structurally sound enough to map, even
     *                      if semantic findings follow
     * @param findings      structural findings
     */
    public record ParseOutcome(
            Optional<PerformanceTestSpecification> specification,
            ValidationResult findings) {

        public boolean isMapped() {
            return specification.isPresent();
        }
    }

    public ParseOutcome parse(String document) {
        List<ValidationFinding> findings = new ArrayList<>();

        if (document == null || document.isBlank()) {
            findings.add(ValidationFinding.error("spec.parse.empty", "",
                    "The submitted specification is empty.",
                    "Submit a PerformanceTest document."));
            return new ParseOutcome(Optional.empty(), ValidationResult.of(findings));
        }

        JsonNode root;
        try {
            root = mapper.readTree(document);
        } catch (JsonProcessingException e) {
            // The parser message can echo document content, so only the location is surfaced.
            var location = e.getLocation();
            String where = location == null ? ""
                    : " at line " + location.getLineNr() + ", column " + location.getColumnNr();
            findings.add(ValidationFinding.error("spec.parse.malformed", "",
                    "The document could not be parsed as YAML or JSON" + where + ".",
                    "Check indentation and quoting."));
            return new ParseOutcome(Optional.empty(), ValidationResult.of(findings));
        }

        if (root == null || root.isNull() || !root.isObject()) {
            findings.add(ValidationFinding.error("spec.parse.notAnObject", "",
                    "The document must be an object with apiVersion, kind, metadata and spec.",
                    null));
            return new ParseOutcome(Optional.empty(), ValidationResult.of(findings));
        }

        rejectUnknownFields(root, "", Set.of("apiVersion", "kind", "metadata", "spec"), findings);

        PerformanceTestSpecification specification = new PerformanceTestSpecification(
                text(root, "apiVersion", "/apiVersion", findings),
                text(root, "kind", "/kind", findings),
                metadata(root.get("metadata"), findings),
                body(root.get("spec"), findings));

        return new ParseOutcome(Optional.of(specification), ValidationResult.of(findings));
    }

    // ── Sections ─────────────────────────────────────────────────────────

    private SpecMetadata metadata(JsonNode node, List<ValidationFinding> findings) {
        if (isAbsent(node)) {
            return null;
        }
        if (!node.isObject()) {
            findings.add(typeError("/metadata", "an object"));
            return null;
        }
        rejectUnknownFields(node, "/metadata",
                Set.of("name", "team", "application", "labels"), findings);

        Map<String, String> labels = new LinkedHashMap<>();
        JsonNode labelsNode = node.get("labels");
        if (!isAbsent(labelsNode)) {
            if (labelsNode.isObject()) {
                labelsNode.fields().forEachRemaining(entry ->
                        labels.put(entry.getKey(), entry.getValue().asText()));
            } else {
                findings.add(typeError("/metadata/labels", "an object"));
            }
        }

        return new SpecMetadata(
                text(node, "name", "/metadata/name", findings),
                text(node, "team", "/metadata/team", findings),
                text(node, "application", "/metadata/application", findings),
                labels);
    }

    private TestSpec body(JsonNode node, List<ValidationFinding> findings) {
        if (isAbsent(node)) {
            return null;
        }
        if (!node.isObject()) {
            findings.add(typeError("/spec", "an object"));
            return null;
        }
        rejectUnknownFields(node, "/spec",
                Set.of("environment", "sources", "authentication", "datasets", "workloads",
                        "objectives", "observability", "governance"),
                findings);

        return new TestSpec(
                environment(node.get("environment"), findings),
                each(node.get("sources"), "/spec/sources", this::source, findings),
                each(node.get("authentication"), "/spec/authentication", this::authentication, findings),
                each(node.get("datasets"), "/spec/datasets", this::dataset, findings),
                each(node.get("workloads"), "/spec/workloads", this::workload, findings),
                each(node.get("objectives"), "/spec/objectives", this::objective, findings),
                observability(node.get("observability"), findings),
                governance(node.get("governance"), findings));
    }

    private EnvironmentSpec environment(JsonNode node, List<ValidationFinding> findings) {
        if (isAbsent(node)) {
            return null;
        }
        if (!node.isObject()) {
            findings.add(typeError("/spec/environment", "an object"));
            return null;
        }
        rejectUnknownFields(node, "/spec/environment",
                Set.of("name", "runnerCell", "targetClass"), findings);

        return new EnvironmentSpec(
                text(node, "name", "/spec/environment/name", findings),
                text(node, "runnerCell", "/spec/environment/runnerCell", findings),
                enumValue(node, "targetClass", "/spec/environment/targetClass",
                        TargetClass::from, findings));
    }

    private SourceSpec source(JsonNode node, String path, List<ValidationFinding> findings) {
        rejectUnknownFields(node, path, Set.of("name", "type", "uri", "git"), findings);

        SourceSpec.GitSource git = null;
        JsonNode gitNode = node.get("git");
        if (!isAbsent(gitNode)) {
            if (gitNode.isObject()) {
                rejectUnknownFields(gitNode, path + "/git",
                        Set.of("repository", "ref", "path", "project"), findings);
                git = new SourceSpec.GitSource(
                        text(gitNode, "repository", path + "/git/repository", findings),
                        text(gitNode, "ref", path + "/git/ref", findings),
                        text(gitNode, "path", path + "/git/path", findings),
                        text(gitNode, "project", path + "/git/project", findings));
            } else {
                findings.add(typeError(path + "/git", "an object"));
            }
        }

        return new SourceSpec(
                text(node, "name", path + "/name", findings),
                enumValue(node, "type", path + "/type", SourceType::from, findings),
                text(node, "uri", path + "/uri", findings),
                git);
    }

    private AuthenticationSpec authentication(JsonNode node, String path,
            List<ValidationFinding> findings) {
        rejectUnknownFields(node, path, Set.of("name", "type", "secretRef"), findings);
        return new AuthenticationSpec(
                text(node, "name", path + "/name", findings),
                text(node, "type", path + "/type", findings),
                text(node, "secretRef", path + "/secretRef", findings));
    }

    private DatasetSpec dataset(JsonNode node, String path, List<ValidationFinding> findings) {
        rejectUnknownFields(node, path, Set.of("name", "source", "partitionByShard"), findings);

        DatasetSpec.DataSource source = null;
        JsonNode sourceNode = node.get("source");
        if (!isAbsent(sourceNode)) {
            if (sourceNode.isObject()) {
                rejectUnknownFields(sourceNode, path + "/source", Set.of("type", "uri"), findings);
                source = new DatasetSpec.DataSource(
                        text(sourceNode, "type", path + "/source/type", findings),
                        text(sourceNode, "uri", path + "/source/uri", findings));
            } else {
                findings.add(typeError(path + "/source", "an object"));
            }
        }

        return new DatasetSpec(
                text(node, "name", path + "/name", findings),
                source,
                bool(node, "partitionByShard", path + "/partitionByShard", findings));
    }

    private WorkloadSpec workload(JsonNode node, String path, List<ValidationFinding> findings) {
        rejectUnknownFields(node, path,
                Set.of("name", "type", "engine", "sourceRef", "model", "scenarios", "startWhen",
                        "browserUsers", "iterationsPerUser"),
                findings);

        StartCondition startWhen = null;
        JsonNode startNode = node.get("startWhen");
        if (!isAbsent(startNode)) {
            if (startNode.isObject()) {
                rejectUnknownFields(startNode, path + "/startWhen",
                        Set.of("workload", "stage"), findings);
                startWhen = new StartCondition(
                        text(startNode, "workload", path + "/startWhen/workload", findings),
                        text(startNode, "stage", path + "/startWhen/stage", findings));
            } else {
                findings.add(typeError(path + "/startWhen", "an object"));
            }
        }

        return new WorkloadSpec(
                text(node, "name", path + "/name", findings),
                enumValue(node, "type", path + "/type", WorkloadType::from, findings),
                text(node, "engine", path + "/engine", findings),
                text(node, "sourceRef", path + "/sourceRef", findings),
                loadModel(node.get("model"), path + "/model", findings),
                each(node.get("scenarios"), path + "/scenarios", this::scenario, findings),
                startWhen,
                integer(node, "browserUsers", path + "/browserUsers", findings),
                integer(node, "iterationsPerUser", path + "/iterationsPerUser", findings));
    }

    private LoadModel loadModel(JsonNode node, String path, List<ValidationFinding> findings) {
        if (isAbsent(node)) {
            return null;
        }
        if (!node.isObject()) {
            findings.add(typeError(path, "an object"));
            return null;
        }
        rejectUnknownFields(node, path, Set.of("type", "stages"), findings);

        return new LoadModel(
                enumValue(node, "type", path + "/type", LoadModelType::from, findings),
                each(node.get("stages"), path + "/stages", this::loadStage, findings));
    }

    private LoadStage loadStage(JsonNode node, String path, List<ValidationFinding> findings) {
        rejectUnknownFields(node, path, Set.of("duration", "targetUsers"), findings);

        Duration duration = duration(node, "duration", path + "/duration", findings);

        // Absence must be reported here rather than defaulting to zero. Zero is a legitimate
        // authored value — a final stage targeting zero users is how ramp-down is expressed — so
        // once the default is applied, a stage that forgot its user count is indistinguishable
        // from one that deliberately generates no load.
        if (!node.hasNonNull("targetUsers")) {
            findings.add(ValidationFinding.error("spec.parse.requiredField.missing",
                    path + "/targetUsers",
                    "targetUsers is required.",
                    "State the target concurrent users for this stage. Use 0 to ramp down."));
        }

        Integer targetUsers = integer(node, "targetUsers", path + "/targetUsers", findings);
        return new LoadStage(duration, targetUsers == null ? 0 : targetUsers);
    }

    private ScenarioSpec scenario(JsonNode node, String path, List<ValidationFinding> findings) {
        rejectUnknownFields(node, path, Set.of("name", "weight", "operationId"), findings);

        Integer weight = integer(node, "weight", path + "/weight", findings);
        return new ScenarioSpec(
                text(node, "name", path + "/name", findings),
                weight == null ? 0 : weight,
                text(node, "operationId", path + "/operationId", findings));
    }

    private ObjectiveSpec objective(JsonNode node, String path, List<ValidationFinding> findings) {
        rejectUnknownFields(node, path,
                Set.of("metric", "scope", "percentile", "threshold"), findings);

        return new ObjectiveSpec(
                text(node, "metric", path + "/metric", findings),
                text(node, "scope", path + "/scope", findings),
                integer(node, "percentile", path + "/percentile", findings),
                threshold(node, path + "/threshold", findings));
    }

    private ObservabilitySpec observability(JsonNode node, List<ValidationFinding> findings) {
        if (isAbsent(node)) {
            return null;
        }
        if (!node.isObject()) {
            findings.add(typeError("/spec/observability", "an object"));
            return null;
        }
        rejectUnknownFields(node, "/spec/observability",
                Set.of("provider", "correlationHeader", "serviceTagSelector", "collect"), findings);

        List<String> collect = new ArrayList<>();
        JsonNode collectNode = node.get("collect");
        if (!isAbsent(collectNode)) {
            if (collectNode.isArray()) {
                collectNode.forEach(item -> collect.add(item.asText()));
            } else {
                findings.add(typeError("/spec/observability/collect", "an array"));
            }
        }

        return new ObservabilitySpec(
                text(node, "provider", "/spec/observability/provider", findings),
                text(node, "correlationHeader", "/spec/observability/correlationHeader", findings),
                text(node, "serviceTagSelector", "/spec/observability/serviceTagSelector", findings),
                collect);
    }

    private GovernanceSpec governance(JsonNode node, List<ValidationFinding> findings) {
        if (isAbsent(node)) {
            return null;
        }
        if (!node.isObject()) {
            findings.add(typeError("/spec/governance", "an object"));
            return null;
        }
        rejectUnknownFields(node, "/spec/governance",
                Set.of("maxDuration", "approvalPolicy", "retention"), findings);

        return new GovernanceSpec(
                duration(node, "maxDuration", "/spec/governance/maxDuration", findings),
                text(node, "approvalPolicy", "/spec/governance/approvalPolicy", findings),
                duration(node, "retention", "/spec/governance/retention", findings));
    }

    // ── Field readers ────────────────────────────────────────────────────

    @FunctionalInterface
    private interface ItemMapper<T> {
        T map(JsonNode node, String path, List<ValidationFinding> findings);
    }

    private <T> List<T> each(JsonNode arrayNode, String path, ItemMapper<T> itemMapper,
            List<ValidationFinding> findings) {
        if (isAbsent(arrayNode)) {
            return List.of();
        }
        if (!arrayNode.isArray()) {
            findings.add(typeError(path, "an array"));
            return List.of();
        }

        List<T> items = new ArrayList<>();
        for (int i = 0; i < arrayNode.size(); i++) {
            JsonNode element = arrayNode.get(i);
            String elementPath = path + "/" + i;
            if (!element.isObject()) {
                findings.add(typeError(elementPath, "an object"));
                continue;
            }
            items.add(itemMapper.map(element, elementPath, findings));
        }
        return items;
    }

    private String text(JsonNode parent, String field, String path,
            List<ValidationFinding> findings) {
        JsonNode node = parent.get(field);
        if (isAbsent(node)) {
            return null;
        }
        if (!node.isValueNode() || node.isArray() || node.isObject()) {
            findings.add(typeError(path, "a string"));
            return null;
        }
        return node.asText();
    }

    private Integer integer(JsonNode parent, String field, String path,
            List<ValidationFinding> findings) {
        JsonNode node = parent.get(field);
        if (isAbsent(node)) {
            return null;
        }
        if (!node.canConvertToInt()) {
            findings.add(typeError(path, "an integer"));
            return null;
        }
        return node.asInt();
    }

    private boolean bool(JsonNode parent, String field, String path,
            List<ValidationFinding> findings) {
        JsonNode node = parent.get(field);
        if (isAbsent(node)) {
            return false;
        }
        if (!node.isBoolean()) {
            findings.add(typeError(path, "true or false"));
            return false;
        }
        return node.asBoolean();
    }

    private Duration duration(JsonNode parent, String field, String path,
            List<ValidationFinding> findings) {
        String raw = text(parent, field, path, findings);
        if (raw == null) {
            return null;
        }
        try {
            return HumanDuration.parse(raw);
        } catch (IllegalArgumentException e) {
            findings.add(ValidationFinding.error("spec.parse.duration.invalid", path,
                    e.getMessage(),
                    "Use a number followed by ms, s, m, h or d, for example 45m."));
            return null;
        }
    }

    private Threshold threshold(JsonNode parent, String path, List<ValidationFinding> findings) {
        JsonNode node = parent.get("threshold");
        if (isAbsent(node)) {
            return null;
        }
        // A bare number reaches here as a numeric node. Accepting it would mean guessing a unit.
        String raw = node.asText();
        try {
            return Threshold.parse(raw);
        } catch (IllegalArgumentException e) {
            findings.add(ValidationFinding.error("spec.parse.threshold.invalid", path,
                    e.getMessage(),
                    "Always state the unit: 750ms, 8s or 1%. A unit-less threshold would have to "
                            + "be guessed, which changes whether the test passes."));
            return null;
        }
    }

    private <E extends Enum<E>> E enumValue(JsonNode parent, String field, String path,
            java.util.function.Function<String, E> factory, List<ValidationFinding> findings) {
        String raw = text(parent, field, path, findings);
        if (raw == null) {
            return null;
        }
        try {
            return factory.apply(raw);
        } catch (IllegalArgumentException e) {
            findings.add(ValidationFinding.error("spec.parse.enum.invalid", path,
                    e.getMessage(), null));
            return null;
        }
    }

    /**
     * Reports fields the schema does not define. Silently ignoring them would let a typo such as
     * {@code targetUser} produce a test that runs at a load nobody chose.
     */
    private void rejectUnknownFields(JsonNode node, String path, Set<String> allowed,
            List<ValidationFinding> findings) {
        node.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) {
                findings.add(ValidationFinding.error("spec.parse.unknownField",
                        path + "/" + field,
                        "Unknown field '" + field + "'.",
                        "Supported fields here: " + String.join(", ", new java.util.TreeSet<>(allowed))));
            }
        });
    }

    private static ValidationFinding typeError(String path, String expected) {
        return ValidationFinding.error("spec.parse.type.invalid", path,
                "Expected " + expected + ".", null);
    }

    private static boolean isAbsent(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode();
    }
}
