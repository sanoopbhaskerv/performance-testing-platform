package com.perfplatform.api.v2.spec;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.perfplatform.domain.spec.HumanDuration;
import com.perfplatform.domain.spec.LoadModelType;
import com.perfplatform.domain.spec.PerformanceTestSpecification;
import com.perfplatform.domain.spec.PerformanceTestSpecification.AuthenticationSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.BrowserProfile;
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
        rejectUnknownFields(node, path, Set.of("name", "type", "uri", "digest", "git"), findings);

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
                text(node, "digest", path + "/digest", findings),
                git);
    }

    private AuthenticationSpec authentication(JsonNode node, String path,
            List<ValidationFinding> findings) {
        rejectUnknownFields(node, path, Set.of("name", "type", "injectAs", "secretRef"), findings);
        return new AuthenticationSpec(
                text(node, "name", path + "/name", findings),
                text(node, "type", path + "/type", findings),
                text(node, "injectAs", path + "/injectAs", findings),
                text(node, "secretRef", path + "/secretRef", findings));
    }

    private DatasetSpec dataset(JsonNode node, String path, List<ValidationFinding> findings) {
        rejectUnknownFields(node, path,
                Set.of("name", "source", "columns", "partitionByShard"), findings);

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

        List<String> columns = new ArrayList<>();
        JsonNode columnsNode = node.get("columns");
        if (!isAbsent(columnsNode)) {
            if (columnsNode.isArray()) {
                columnsNode.forEach(item -> columns.add(item.asText()));
            } else {
                findings.add(typeError(path + "/columns", "an array"));
            }
        }

        return new DatasetSpec(
                text(node, "name", path + "/name", findings),
                source,
                columns,
                bool(node, "partitionByShard", path + "/partitionByShard", findings));
    }

    private WorkloadSpec workload(JsonNode node, String path, List<ValidationFinding> findings) {
        rejectUnknownFields(node, path,
                Set.of("name", "type", "engine", "sourceRef", "authRef", "model", "scenarios",
                        "startWhen", "browserUsers", "iterationsPerUser", "browserProfile"),
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
                text(node, "authRef", path + "/authRef", findings),
                loadModel(node.get("model"), path + "/model", findings),
                each(node.get("scenarios"), path + "/scenarios", this::scenario, findings),
                startWhen,
                integer(node, "browserUsers", path + "/browserUsers", findings),
                integer(node, "iterationsPerUser", path + "/iterationsPerUser", findings),
                browserProfile(node.get("browserProfile"), path + "/browserProfile", findings));
    }

    private BrowserProfile browserProfile(JsonNode node, String path,
            List<ValidationFinding> findings) {
        if (isAbsent(node)) {
            return null;
        }
        if (!node.isObject()) {
            findings.add(typeError(path, "an object"));
            return null;
        }
        rejectUnknownFields(node, path, Set.of("device", "network", "cpuThrottle"), findings);
        return new BrowserProfile(
                text(node, "device", path + "/device", findings),
                text(node, "network", path + "/network", findings),
                integer(node, "cpuThrottle", path + "/cpuThrottle", findings));
    }

    private LoadModel loadModel(JsonNode node, String path, List<ValidationFinding> findings) {
        if (isAbsent(node)) {
            return null;
        }
        if (!node.isObject()) {
            findings.add(typeError(path, "an object"));
            return null;
        }
        rejectUnknownFields(node, path, Set.of("type", "stages", "maxConcurrency"), findings);

        LoadModelType type = enumValue(node, "type", path + "/type", LoadModelType::from, findings);

        // The stage parser needs to know which quantity this model reads, so it can say which field
        // is missing rather than reporting the closed model's requirement to somebody writing an
        // open one. A model whose type failed to parse is read as closed: its stages will be
        // reported against the type the document appears to have asked for.
        boolean arrivalRate = type != null && type.isOpen();

        return new LoadModel(
                type,
                each(node.get("stages"), path + "/stages",
                        (stageNode, stagePath, stageFindings) ->
                                loadStage(stageNode, stagePath, stageFindings, arrivalRate),
                        findings),
                integer(node, "maxConcurrency", path + "/maxConcurrency", findings));
    }

    private LoadStage loadStage(JsonNode node, String path, List<ValidationFinding> findings,
            boolean arrivalRate) {

        rejectUnknownFields(node, path, Set.of("name", "duration", "targetUsers", "targetRate"), findings);

        Duration duration = duration(node, "duration", path + "/duration", findings);

        // Absence must be reported here rather than defaulting to zero. Zero is a legitimate
        // authored value — a final stage targeting zero is how ramp-down is expressed — so once the
        // default is applied, a stage that forgot its target is indistinguishable from one that
        // deliberately generates no load.
        String required = arrivalRate ? "targetRate" : "targetUsers";
        if (!node.hasNonNull(required)) {
            findings.add(ValidationFinding.error("spec.parse.requiredField.missing",
                    path + "/" + required,
                    required + " is required.",
                    arrivalRate
                            ? "State the requests per second for this stage. Use 0 to ramp down."
                            : "State the target concurrent users for this stage. Use 0 to ramp "
                                    + "down."));
        }

        Integer targetUsers = integer(node, "targetUsers", path + "/targetUsers", findings);
        Integer targetRate = integer(node, "targetRate", path + "/targetRate", findings);

        // Both are carried through as authored, including the one this model does not read. The
        // semantic validator refuses a stage that sets the wrong field, and it can only do that if
        // the parser has not already dropped it.
        return new LoadStage(text(node, "name", path + "/name", findings),
                duration, targetUsers == null ? 0 : targetUsers, targetRate);
    }

    private ScenarioSpec scenario(JsonNode node, String path, List<ValidationFinding> findings) {
        rejectUnknownFields(node, path,
                Set.of("name", "weight", "operationId", "method", "path",
                        "headers", "query", "body", "steps"),
                findings);

        Integer weight = integer(node, "weight", path + "/weight", findings);
        return new ScenarioSpec(
                text(node, "name", path + "/name", findings),
                weight == null ? 0 : weight,
                text(node, "operationId", path + "/operationId", findings),
                text(node, "method", path + "/method", findings),
                text(node, "path", path + "/path", findings),
                stringMap(node, "headers", path + "/headers", findings),
                stringMap(node, "query", path + "/query", findings),
                requestBody(node.get("body"), path + "/body", findings),
                each(node.get("steps"), path + "/steps", this::step, findings));
    }

    /**
     * One request within a journey.
     *
     * <p>Unknown fields are rejected here as everywhere else: a typo in {@code extract} would
     * otherwise be silently dropped, and a journey that quietly stopped correlating would send every
     * later request with an unfilled reference — the failure this whole increment exists to prevent.
     */
    private PerformanceTestSpecification.StepSpec step(JsonNode node, String path,
            List<ValidationFinding> findings) {

        rejectUnknownFields(node, path,
                Set.of("name", "method", "path", "headers", "query", "body",
                        "extract", "expect", "thinkTime"),
                findings);

        return new PerformanceTestSpecification.StepSpec(
                text(node, "name", path + "/name", findings),
                text(node, "method", path + "/method", findings),
                text(node, "path", path + "/path", findings),
                stringMap(node, "headers", path + "/headers", findings),
                stringMap(node, "query", path + "/query", findings),
                requestBody(node.get("body"), path + "/body", findings),
                each(node.get("extract"), path + "/extract", this::extraction, findings),
                each(node.get("expect"), path + "/expect", this::assertion, findings),
                duration(node, "thinkTime", path + "/thinkTime", findings));
    }

    /** A value taken out of a response for later steps to use. */
    private PerformanceTestSpecification.ExtractionSpec extraction(JsonNode node, String path,
            List<ValidationFinding> findings) {

        rejectUnknownFields(node, path, Set.of("name", "from", "expression", "required"), findings);

        JsonNode required = node.get("required");
        return new PerformanceTestSpecification.ExtractionSpec(
                text(node, "name", path + "/name", findings),
                enumValue(node, "from", path + "/from",
                        PerformanceTestSpecification.ExtractionSource::from, findings),
                text(node, "expression", path + "/expression", findings),
                // Required unless explicitly turned off. An extraction that silently became optional
                // because a field was missing would remove the guard that makes a broken correlation
                // visible.
                required == null || required.isNull() || !required.isBoolean() || required.asBoolean());
    }

    /** What must be true of a response. */
    private PerformanceTestSpecification.AssertionSpec assertion(JsonNode node, String path,
            List<ValidationFinding> findings) {

        rejectUnknownFields(node, path, Set.of("subject", "operator", "value"), findings);

        return new PerformanceTestSpecification.AssertionSpec(
                enumValue(node, "subject", path + "/subject",
                        PerformanceTestSpecification.AssertionSubject::from, findings),
                enumValue(node, "operator", path + "/operator",
                        PerformanceTestSpecification.AssertionOperator::from, findings),
                textOrNumber(node, "value", path + "/value", findings));
    }

    /**
     * A value that may be written as a number or a string.
     *
     * <p>{@code value: 200} and {@code value: "200"} are the same expectation, and demanding quotes
     * around a status code is the kind of friction that teaches people the tool is fussy rather than
     * careful.
     */
    private String textOrNumber(JsonNode parent, String field, String path,
            List<ValidationFinding> findings) {
        JsonNode node = parent.get(field);
        if (node != null && node.isNumber()) {
            return node.asText();
        }
        return text(parent, field, path, findings);
    }


    /**
     * A body, or null when the scenario sends none.
     *
     * <p>A bare string is accepted as shorthand for {@code {content: "…"}}, because that is how
     * almost every body is written and demanding the longer form for the common case is friction
     * with no safety benefit.
     */
    private ScenarioSpec.RequestBody requestBody(JsonNode node, String path,
            List<ValidationFinding> findings) {

        if (isAbsent(node)) {
            return null;
        }
        if (node.isTextual()) {
            return new ScenarioSpec.RequestBody(null, node.asText());
        }
        if (!node.isObject()) {
            findings.add(typeError(path, "an object or a string"));
            return null;
        }

        rejectUnknownFields(node, path, Set.of("contentType", "content"), findings);
        return new ScenarioSpec.RequestBody(
                text(node, "contentType", path + "/contentType", findings),
                text(node, "content", path + "/content", findings));
    }

    /**
     * A map of strings, preserving authoring order.
     *
     * <p>Values are read as text rather than coerced: a header written as {@code X-Retries: 3}
     * is sent as the string {@code 3}, which is what a header is. Coercing it to a number and back
     * would be a chance to change what gets sent.
     */
    private Map<String, String> stringMap(JsonNode parent, String field, String path,
            List<ValidationFinding> findings) {

        JsonNode node = parent.get(field);
        if (isAbsent(node)) {
            return Map.of();
        }
        if (!node.isObject()) {
            findings.add(typeError(path, "an object"));
            return Map.of();
        }

        Map<String, String> values = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            if (value.isObject() || value.isArray()) {
                findings.add(typeError(path + "/" + entry.getKey(), "a single value"));
            } else {
                values.put(entry.getKey(), value.asText());
            }
        });
        return values;
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
                    "Always state the unit: 750ms, 8s, 1%, or 0.1score. A unit-less threshold "
                            + "would have to be guessed, which changes whether the test passes."));
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
