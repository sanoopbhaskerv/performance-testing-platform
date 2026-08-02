package com.perfplatform.importer.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.perfplatform.domain.importer.SourceImporter;
import com.perfplatform.domain.spec.PerformanceTestSpecification.ScenarioSpec;
import com.perfplatform.domain.spec.SourceType;
import com.perfplatform.domain.validation.SecretValueDetector;
import com.perfplatform.domain.validation.ValidationFinding;
import com.perfplatform.domain.validation.ValidationResult;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns an OpenAPI 3 or Swagger 2 document into draft scenarios.
 *
 * <p>The document a team already publishes is the only source that describes <em>every</em>
 * operation an API has, which makes it the fastest way from "we have an API" to "we have a draft
 * test". It is also the source most likely to be mistaken for a finished test, because it looks
 * complete — so most of the work here is being explicit about the three things it cannot tell you.
 *
 * <h2>What the document cannot say, and is not guessed</h2>
 * <ul>
 *   <li><b>Which operations matter.</b> Every operation is imported with an equal weight. A real
 *       traffic mix is a decision about the business, and a person makes it; equal weights are
 *       visibly a starting point rather than a plausible-looking claim.</li>
 *   <li><b>What data to send.</b> Values come from {@code example}, {@code examples},
 *       {@code default} and {@code enum} — things a person wrote in the document. A schema saying a
 *       field is a string is not a value, and inventing one produces a test that exercises the
 *       target's validation-failure paths at full load and reports it as latency. Where a value is
 *       required and none is stated, this importer emits a finding and leaves the gap visible. See
 *       {@link StatedValues}.</li>
 *   <li><b>Where to send it.</b> The host is reported and dropped; a run's traffic goes to the
 *       target registered for its application. The path prefix from the server URL is kept, because
 *       that is part of the request rather than part of the address.</li>
 * </ul>
 *
 * <h2>operationId is preserved</h2>
 * CLAUDE.md §23 requires it, and the reason shows up later: a scenario name has to be a short
 * lowercase identifier — it ends up in artifact object keys and Kubernetes labels — so
 * {@code listOrdersByCustomer} becomes the name {@code list-orders-by-customer} while the
 * {@code operationId} survives verbatim. That is what lets a report be matched back to the operation
 * in the document, and a re-import be matched to the scenario it replaces.
 *
 * <h2>Credentials are not imported</h2>
 * An OpenAPI document is a published artifact, but its examples are frequently copied from a real
 * session. Parameters named as credentials are refused by name, token-shaped example values are
 * refused by shape, and every refusal is reported without quoting what it refused.
 */
public final class OpenApiImporter implements SourceImporter {

    /**
     * A ceiling on how many operations become scenarios.
     *
     * <p>A large enterprise document runs to hundreds of operations, and a draft with four hundred
     * equally weighted scenarios is not a test anyone will edit into one. The cap is reported, and
     * the remedy — decide which journeys matter — is the work the importer cannot do anyway.
     */
    static final int MAX_OPERATIONS = 200;

    /**
     * Methods that become scenarios, in the order they are read from a path item.
     *
     * <p>{@code trace} is absent: the specification's supported-method set excludes it, and a
     * scenario declaring it would be refused at validation. It is reported as skipped instead.
     */
    private static final List<String> METHODS =
            List.of("get", "post", "put", "patch", "delete", "options", "head");

    private static final Set<String> METHODS_WITHOUT_BODY = Set.of("GET", "HEAD");

    /**
     * Headers a client negotiates rather than an author choosing.
     *
     * <p>Importing them would pin the test to whatever the document's author happened to write, and
     * the runner sets them itself.
     */
    private static final Set<String> CLIENT_NEGOTIATED_HEADERS =
            Set.of("content-length", "host", "connection");

    private static final Pattern PATH_PLACEHOLDER = Pattern.compile("\\{([^}]+)}");

    /** Names must be lowercase alphanumeric with hyphens, and at most this long. */
    private static final int MAX_NAME_LENGTH = 63;

    @Override
    public SourceType sourceType() {
        return SourceType.OPENAPI;
    }

    @Override
    public ImportResult importFrom(String document) {
        List<ValidationFinding> findings = new ArrayList<>();

        if (document == null || document.isBlank()) {
            return ImportResult.nothing(ValidationResult.of(ValidationFinding.error(
                    "import.openapi.empty", "/", "No document was provided.",
                    "Paste or upload an OpenAPI 3 or Swagger 2 document, in YAML or JSON.")));
        }

        Optional<OpenApiDocument> parsed = OpenApiDocument.parse(document);
        if (parsed.isEmpty()) {
            return ImportResult.nothing(ValidationResult.of(ValidationFinding.error(
                    "import.openapi.unreadable", "/",
                    "This could not be read as an OpenAPI or Swagger document.",
                    "It must be valid YAML or JSON with a top-level 'openapi' or 'swagger' "
                            + "version field.")));
        }

        OpenApiDocument api = parsed.get();
        JsonNode paths = api.paths();
        if (paths.isEmpty()) {
            return ImportResult.nothing(ValidationResult.of(ValidationFinding.error(
                    "import.openapi.paths.missing", "/paths",
                    "The document declares no paths, so there are no operations to import.", null)));
        }

        Reading reading = new Reading(api, findings);
        List<Draft> drafts = reading.readAll(paths);
        reading.reportDocumentLevelFindings();

        if (drafts.isEmpty()) {
            findings.add(ValidationFinding.error("import.openapi.operations.none", "/paths",
                    "The document declares paths but no supported operations.",
                    "Supported methods are GET, POST, PUT, PATCH, DELETE, OPTIONS and HEAD."));
            return ImportResult.nothing(ValidationResult.of(findings));
        }

        // Weighting adds a finding of its own, so it runs before the findings are collected.
        List<ScenarioSpec> scenarios = withWeights(drafts, findings);
        return ImportResult.of(scenarios, ValidationResult.of(findings));
    }

    /** One operation, read but not yet weighted: a weight depends on how many others there are. */
    private record Draft(
            String name,
            String operationId,
            String method,
            String path,
            Map<String, String> headers,
            Map<String, String> query,
            ScenarioSpec.RequestBody body) {
    }

    /**
     * Weights that sum to 100, so the draft does not arrive with a warning already attached.
     *
     * <p>Equal shares, because the document says nothing about traffic mix and a fabricated
     * distribution would look like a decision somebody made. The remainder goes to the first few
     * scenarios rather than being dropped.
     *
     * <p>Above a hundred operations an equal share rounds to zero, which the specification refuses —
     * so every scenario gets a weight of 1 and the resulting "weights sum to N, not 100" warning is
     * the correct thing for someone to see: a hundred-scenario test needs a mix chosen by a person.
     */
    private static List<ScenarioSpec> withWeights(List<Draft> drafts,
            List<ValidationFinding> findings) {
        int count = drafts.size();
        int base = count > 100 ? 1 : 100 / count;
        int remainder = count > 100 ? 0 : 100 % count;

        findings.add(ValidationFinding.info("import.openapi.weights.equal", "/scenarios",
                "Every operation was imported with an equal weight. The document does not describe "
                        + "how much traffic each operation actually receives, so set the mix from "
                        + "what you know about production before drawing conclusions from a run."));

        List<ScenarioSpec> scenarios = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Draft draft = drafts.get(i);
            scenarios.add(new ScenarioSpec(
                    draft.name(),
                    base + (i < remainder ? 1 : 0),
                    draft.operationId(),
                    draft.method(),
                    draft.path(),
                    draft.headers(),
                    draft.query(),
                    draft.body()));
        }
        return scenarios;
    }

    /**
     * One pass over one document.
     *
     * <p>An instance per import, so the counters that aggregate findings — how many values were
     * taken from the document, whether any operation is secured — are not shared state. Several
     * findings are deliberately raised once for the whole document rather than once per operation: a
     * three-hundred-operation import that emitted one finding per parameter would bury the handful
     * that need a decision.
     */
    private static final class Reading {

        private final OpenApiDocument api;
        private final List<ValidationFinding> findings;
        private final String basePath;
        private final Set<String> usedNames = new LinkedHashSet<>();

        private int statedValuesUsed;
        private boolean sawSecurity;
        private boolean sawTrace;
        private boolean sawBody;
        private boolean truncated;

        private Reading(OpenApiDocument api, List<ValidationFinding> findings) {
            this.api = api;
            this.findings = findings;
            this.basePath = api.basePath();
        }

        private List<Draft> readAll(JsonNode paths) {
            List<Draft> drafts = new ArrayList<>();
            sawSecurity = declaresSecurity(api.root());

            Iterator<Map.Entry<String, JsonNode>> entries = paths.fields();
            while (entries.hasNext()) {
                Map.Entry<String, JsonNode> entry = entries.next();
                JsonNode pathItem = api.resolve(entry.getValue());
                if (pathItem == null || !pathItem.isObject()) {
                    continue;
                }
                if (pathItem.has("trace")) {
                    sawTrace = true;
                }

                for (String method : METHODS) {
                    JsonNode operation = api.resolve(pathItem.path(method));
                    if (operation == null || !operation.isObject()) {
                        continue;
                    }
                    if (drafts.size() >= MAX_OPERATIONS) {
                        truncated = true;
                        continue;
                    }
                    drafts.add(readOperation(entry.getKey(), method, operation,
                            pathItem.path("parameters")));
                }
            }
            return drafts;
        }

        private Draft readOperation(String pathTemplate, String method, JsonNode operation,
                JsonNode sharedParameters) {

            String where = "/paths" + pathTemplate + "/" + method;
            String upperMethod = method.toUpperCase(Locale.ROOT);
            String operationId = textOrNull(operation.get("operationId"));

            if (operation.path("deprecated").asBoolean(false)) {
                findings.add(ValidationFinding.info("import.openapi.operation.deprecated", where,
                        "Operation '" + (operationId != null ? operationId : upperMethod + " "
                                + pathTemplate) + "' is marked deprecated in the document. It was "
                                + "imported; remove it unless you mean to load-test it."));
            }
            if (declaresSecurity(operation)) {
                sawSecurity = true;
            }

            Map<String, JsonNode> parameters = mergeParameters(sharedParameters,
                    operation.path("parameters"));

            Map<String, String> headers = new LinkedHashMap<>();
            Map<String, String> query = new LinkedHashMap<>();
            Map<String, String> pathValues = new LinkedHashMap<>();
            JsonNode bodyParameter = null;

            for (JsonNode parameter : parameters.values()) {
                String name = textOrNull(parameter.get("name"));
                String in = textOrNull(parameter.get("in"));
                if (name == null || in == null) {
                    continue;
                }
                switch (in.toLowerCase(Locale.ROOT)) {
                    // Never reported as missing here: an absent path value is reported once, and
                    // more sharply, by substitutePathParameters.
                    case "path" -> readValueInto(pathValues, name, parameter, where + "/path", false);
                    case "query" -> readQueryParameter(query, name, parameter, where);
                    case "header" -> readHeaderParameter(headers, name, parameter, where);
                    case "cookie" -> findings.add(ValidationFinding.warning(
                            "import.openapi.parameter.cookie", where + "/cookie/" + name,
                            "Cookie parameter '" + name + "' was not imported.",
                            "A cookie carrying a session is a credential; declare it under "
                                    + "spec.authentication as a secret reference. A cookie carrying "
                                    + "anything else can be sent as a header you set explicitly."));
                    case "body" -> bodyParameter = parameter;
                    case "formdata" -> findings.add(ValidationFinding.warning(
                            "import.openapi.parameter.formData", where + "/formData/" + name,
                            "Form-data parameter '" + name + "' was not imported.",
                            "State the request body on the scenario, or attach a dataset."));
                    default -> {
                        // An unknown location. Nothing sensible to send it as, so it is left out.
                    }
                }
            }

            String path = basePath + substitutePathParameters(pathTemplate, pathValues, where);
            ScenarioSpec.RequestBody body = readBody(operation, bodyParameter, upperMethod, where);

            return new Draft(uniqueName(operationId, upperMethod, pathTemplate), operationId,
                    upperMethod, path, headers, query, body);
        }

        // ── Parameters ───────────────────────────────────────────────────

        /**
         * Path-level parameters, overridden by operation-level ones.
         *
         * <p>Keyed by name <em>and</em> location, which is the specification's own identity rule: a
         * document may legitimately declare {@code id} in both the path and the query.
         */
        private Map<String, JsonNode> mergeParameters(JsonNode shared, JsonNode own) {
            Map<String, JsonNode> merged = new LinkedHashMap<>();
            for (JsonNode source : new JsonNode[] {shared, own}) {
                if (!source.isArray()) {
                    continue;
                }
                for (JsonNode raw : source) {
                    JsonNode parameter = api.resolve(raw);
                    if (parameter == null || !parameter.isObject()) {
                        continue;
                    }
                    merged.put(textOrNull(parameter.get("name")) + "|"
                            + textOrNull(parameter.get("in")), parameter);
                }
            }
            return merged;
        }

        private void readQueryParameter(Map<String, String> query, String name, JsonNode parameter,
                String where) {
            if (SecretValueDetector.isCredentialHeader(name)) {
                // Sharper than the header case: a query string is written to every access log
                // between the runner and the target.
                findings.add(ValidationFinding.warning("import.openapi.parameter.credential",
                        where + "/query/" + name,
                        "Query parameter '" + name + "' names a credential and was not imported.",
                        "Declare it under spec.authentication as a secret reference."));
                return;
            }
            readValueInto(query, name, parameter, where + "/query", isRequired(parameter));
        }

        private void readHeaderParameter(Map<String, String> headers, String name,
                JsonNode parameter, String where) {
            if (SecretValueDetector.isCredentialHeader(name)) {
                findings.add(ValidationFinding.warning("import.openapi.parameter.credential",
                        where + "/headers/" + name,
                        "Header '" + name + "' carries authentication and was not imported.",
                        "Declare it under spec.authentication as a secret reference. The runner "
                                + "resolves it at execution time, so it never enters the "
                                + "specification."));
                return;
            }
            if (CLIENT_NEGOTIATED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                return;
            }
            readValueInto(headers, name, parameter, where + "/headers", isRequired(parameter));
        }

        /**
         * Puts the value the document states, or reports that it states none.
         *
         * <p>An optional parameter with no stated value is simply left out — omitting it is a valid
         * request, and a finding for each would be noise. A required one is reported, because a
         * request missing it will be rejected by the target at whatever load the test runs.
         */
        private void readValueInto(Map<String, String> target, String name, JsonNode parameter,
                String where, boolean required) {

            Optional<StatedValues.Stated> stated = StatedValues.forParameter(parameter, api);

            if (stated.isEmpty()) {
                if (required) {
                    findings.add(ValidationFinding.warning("import.openapi.value.missing",
                            where + "/" + name,
                            "Required parameter '" + name + "' has no example, default or enum "
                                    + "value in the document, so none was imported.",
                            "Add an 'example' to the document, set the value on the scenario, or "
                                    + "attach a dataset. A value was not invented: a made-up "
                                    + "identifier would measure the target rejecting the request."));
                }
                return;
            }

            String value = stated.get().value();
            if (SecretValueDetector.looksLikeCredential(value)) {
                findings.add(ValidationFinding.warning("import.openapi.parameter.credential",
                        where + "/" + name,
                        "'" + name + "' had an example that looked like a credential, and it was "
                                + "not imported.",
                        "Declare it under spec.authentication as a secret reference."));
                return;
            }

            statedValuesUsed++;
            target.put(name, value);
        }

        /**
         * The path with its placeholders filled in where the document says what fills them.
         *
         * <p>An unresolved placeholder is left in place and reported as an <b>error</b>. It is the
         * one gap that is not survivable: {@code /orders/{orderId}} sent literally 404s on every
         * request, and a run made of 404s reports a fast, healthy-looking latency for a target that
         * did no work. The scenario is still returned so it can be filled in — the finding is what
         * says it is not ready.
         */
        private String substitutePathParameters(String template, Map<String, String> values,
                String where) {
            Matcher matcher = PATH_PLACEHOLDER.matcher(template);
            StringBuilder result = new StringBuilder();

            while (matcher.find()) {
                String name = matcher.group(1);
                String value = values.get(name);
                if (value == null) {
                    findings.add(ValidationFinding.error("import.openapi.path.parameter.unresolved",
                            where + "/path/" + name,
                            "Path parameter '" + name + "' has no example, default or enum value in "
                                    + "the document, so '" + matcher.group(0) + "' was left in the "
                                    + "path.",
                            "Set the value on the scenario or attach a dataset before running. Sent "
                                    + "as it stands, every request returns 404 and the run reports "
                                    + "the latency of an error path."));
                    matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group(0)));
                } else {
                    // Encoded here, unlike a query value: the renderer encodes query parameters
                    // itself, but a path is written into the request line as given, so a value
                    // containing '?' or '/' would change which resource is addressed.
                    matcher.appendReplacement(result, Matcher.quoteReplacement(encodeSegment(value)));
                }
            }
            matcher.appendTail(result);
            return result.toString();
        }

        // ── Request body ─────────────────────────────────────────────────

        /**
         * The body the document states, in whichever dialect it states it.
         *
         * <p>A schema is never turned into a body. Generating one from field types produces a
         * request the target rejects, sent at full load, which is the specific failure this importer
         * exists to avoid.
         */
        private ScenarioSpec.RequestBody readBody(JsonNode operation, JsonNode bodyParameter,
                String method, String where) {

            boolean declaresBody = operation.has("requestBody") || bodyParameter != null;
            if (!declaresBody) {
                return null;
            }
            if (METHODS_WITHOUT_BODY.contains(method)) {
                // Servers and proxies drop a body on GET or HEAD unpredictably, so a test sending
                // one measures a request other than the one written — a wrong answer, not a failure.
                findings.add(ValidationFinding.warning("import.openapi.body.onMethodWithoutBody",
                        where + "/requestBody",
                        "The document declares a request body for a " + method + ", which was not "
                                + "imported.",
                        "Many servers and proxies drop a body on " + method + ", so the test would "
                                + "measure a different request than the one written."));
                return null;
            }

            Optional<StatedValues.Stated> stated;
            String contentType;

            if (bodyParameter != null) {
                // Swagger 2: the body is a parameter, and the media type comes from `consumes`.
                stated = StatedValues.forMediaType(bodyParameter, api);
                contentType = firstConsumes(operation);
            } else {
                JsonNode content = api.resolve(operation.path("requestBody")).path("content");
                if (!content.isObject() || content.isEmpty()) {
                    return null;
                }
                contentType = preferredMediaType(content);
                if (contentType == null) {
                    return null;
                }
                stated = StatedValues.forMediaType(api.resolve(content.path(contentType)), api);
            }

            if (stated.isEmpty()) {
                findings.add(ValidationFinding.warning("import.openapi.body.missing",
                        where + "/requestBody",
                        "This operation takes a request body and the document states no example, "
                                + "so none was imported.",
                        "Add an 'example' to the document, write the body on the scenario, or "
                                + "attach a dataset. A body was not generated from the schema: it "
                                + "would be the right shape with invented values, and the run would "
                                + "measure the target rejecting it."));
                return null;
            }

            String content = stated.get().value();
            if (SecretValueDetector.looksLikeCredential(content)) {
                findings.add(ValidationFinding.warning("import.openapi.body.credential",
                        where + "/requestBody",
                        "The example request body looked like a credential and was not imported.",
                        null));
                return null;
            }

            sawBody = true;
            statedValuesUsed++;
            return new ScenarioSpec.RequestBody(contentType, content);
        }

        /**
         * The media type to send.
         *
         * <p>JSON where the operation offers it, because that is what an API load test almost always
         * is, and because a JSON example is the one most likely to be present and complete.
         */
        private static String preferredMediaType(JsonNode content) {
            if (content.has("application/json")) {
                return "application/json";
            }
            Iterator<String> names = content.fieldNames();
            String first = null;
            while (names.hasNext()) {
                String name = names.next();
                if (first == null) {
                    first = name;
                }
                if (name.toLowerCase(Locale.ROOT).contains("json")) {
                    return name;
                }
            }
            return first;
        }

        private String firstConsumes(JsonNode operation) {
            for (JsonNode source : new JsonNode[] {operation.path("consumes"),
                    api.root().path("consumes")}) {
                if (source.isArray() && !source.isEmpty()) {
                    return source.get(0).asText("application/json");
                }
            }
            return "application/json";
        }

        // ── Naming ───────────────────────────────────────────────────────

        /**
         * A scenario name: the operationId in the form names are allowed to take.
         *
         * <p>Names reach artifact object keys and Kubernetes labels, so they are lowercase
         * alphanumeric with hyphens and at most 63 characters — which is why {@code operationId} is
         * carried separately rather than being reshaped and lost.
         */
        private String uniqueName(String operationId, String method, String pathTemplate) {
            String base = operationId != null && !operationId.isBlank()
                    ? kebab(operationId)
                    : kebab(method + "-" + pathTemplate.replace("{", "").replace("}", ""));
            if (base.isEmpty()) {
                base = "operation";
            }

            String candidate = truncate(base);
            for (int suffix = 2; !usedNames.add(candidate); suffix++) {
                String tail = "-" + suffix;
                candidate = truncate(base, MAX_NAME_LENGTH - tail.length()) + tail;
            }
            return candidate;
        }

        /** {@code listOrdersByCustomer} to {@code list-orders-by-customer}. */
        private static String kebab(String value) {
            String spaced = value.replaceAll("([a-z0-9])([A-Z])", "$1-$2");
            String hyphenated = spaced.replaceAll("[^A-Za-z0-9]+", "-")
                    .toLowerCase(Locale.ROOT);
            return hyphenated.replaceAll("^-+", "").replaceAll("-+$", "")
                    .replaceAll("-{2,}", "-");
        }

        private static String truncate(String value) {
            return truncate(value, MAX_NAME_LENGTH);
        }

        private static String truncate(String value, int limit) {
            String cut = value.length() <= limit ? value : value.substring(0, limit);
            return cut.replaceAll("-+$", "");
        }

        // ── Document-level findings ──────────────────────────────────────

        /**
         * The findings that belong to the document rather than to one operation.
         *
         * <p>Raised once, deliberately. A three-hundred-operation import that reported "this value
         * came from an example" three hundred times would bury the handful of findings that need
         * somebody to decide something.
         */
        private void reportDocumentLevelFindings() {
            api.declaredHost().ifPresent(host -> findings.add(ValidationFinding.info(
                    "import.openapi.host.dropped", "/",
                    "The host '" + host + "' was not imported; only paths were. A run's traffic "
                            + "goes to the target registered for its application, so check that "
                            + "this test's metadata names the application you intend."
                            + (basePath.isEmpty() ? "" : " The path prefix '" + basePath
                            + "' was kept, because it is part of the request rather than the "
                            + "address."))));

            if (api.basePathUnresolved()) {
                findings.add(ValidationFinding.warning("import.openapi.basePath.unresolved", "/",
                        "The server URL contains a variable with no default, so no path prefix "
                                + "could be determined and paths were imported as written.",
                        "Check whether these paths need a prefix such as '/v1'."));
            }

            if (api.sawExternalRef()) {
                findings.add(ValidationFinding.warning("import.openapi.ref.external", "/",
                        "The document references another file or URL, and those references were "
                                + "not followed, so anything defined in them was not imported.",
                        "Provide a bundled document with all references resolved into it. The "
                                + "platform does not fetch URLs named by an uploaded document."));
            }

            if (sawSecurity) {
                findings.add(ValidationFinding.warning("import.openapi.security.declared", "/",
                        "The document declares security requirements. No credential was imported.",
                        "Declare authentication under spec.authentication as a secret reference; "
                                + "the runner resolves it at execution time. Without it, the run "
                                + "measures the target rejecting every request."));
            }

            if (sawTrace) {
                findings.add(ValidationFinding.info("import.openapi.method.trace", "/paths",
                        "TRACE operations were skipped. The platform does not support TRACE, and "
                                + "it is disabled on most servers."));
            }

            if (statedValuesUsed > 0) {
                findings.add(ValidationFinding.info("import.openapi.values.fromDocument", "/",
                        statedValuesUsed + " request value(s) came from example, default or enum "
                                + "values stated in the document. They are valid shapes; they are "
                                + "not necessarily data that exists in the environment this test "
                                + "will run against. Check them, or attach a dataset."));
            }

            if (sawBody) {
                findings.add(ValidationFinding.info("import.openapi.body.static", "/",
                        "Imported request bodies are fixed, so every virtual user sends the same "
                                + "payload. For a test that writes data, vary it with a dataset — "
                                + "otherwise the run measures the target's behaviour on one "
                                + "repeated request."));
            }

            if (truncated) {
                findings.add(ValidationFinding.warning("import.openapi.operations.truncated",
                        "/paths",
                        "The document declares more than " + MAX_OPERATIONS + " operations, and "
                                + "only the first " + MAX_OPERATIONS + " were imported.",
                        "Import the part of the API you mean to test. A test covering every "
                                + "operation equally does not resemble any real traffic."));
            }
        }

        private static boolean declaresSecurity(JsonNode node) {
            JsonNode security = node.path("security");
            return security.isArray() && !security.isEmpty();
        }

        private static boolean isRequired(JsonNode parameter) {
            return parameter.path("required").asBoolean(false);
        }

        private static String textOrNull(JsonNode node) {
            return node == null || !node.isTextual() || node.asText().isBlank()
                    ? null : node.asText();
        }

        /** Percent-encodes a value going into a path segment, leaving a space as {@code %20}. */
        private static String encodeSegment(String value) {
            return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
        }
    }
}
