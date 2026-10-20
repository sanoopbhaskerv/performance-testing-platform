package com.perfplatform.importer.postman;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.perfplatform.domain.importer.SourceImporter;
import com.perfplatform.domain.spec.PerformanceTestSpecification.ScenarioSpec;
import com.perfplatform.domain.spec.SourceType;
import com.perfplatform.domain.validation.SecretValueDetector;
import com.perfplatform.domain.validation.SensitiveFieldDetector;
import com.perfplatform.domain.validation.ValidationFinding;
import com.perfplatform.domain.validation.ValidationResult;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Turns a Postman Collection into draft scenarios grouped by folder.
 *
 * <p>A collection is the source closest to a test that already exists. Somebody curated these
 * requests, named them in words a person chose, arranged them into folders that usually correspond
 * to real journeys, and — unlike an OpenAPI document — made them actually work. That is most of what
 * an importer wants and cannot otherwise get, which is why the names and the folder structure are
 * carried across rather than derived from paths.
 *
 * <h2>It is also the only source that carries executable code</h2>
 * Collections hold pre-request and test scripts, and they are the reason CLAUDE.md §23 says a
 * Postman script is <em>flagged, never evaluated</em>. This importer never looks at what a script
 * does; it reports that one exists and which request it belongs to. A script commonly performs the
 * sign-in that makes the rest of the collection work, so a collection whose scripts are ignored can
 * import cleanly and then fail entirely at run time — saying so is the difference between a draft
 * somebody can use and one that wastes an afternoon.
 *
 * <h2>And it leaks credentials more quietly than a HAR does</h2>
 * Everyone knows a capture is sensitive. Nobody thinks that about a collection they share with a
 * teammate — yet an exported collection carries its variable values, and a variable named
 * {@code accessToken} usually holds a working one. Auth blocks, credential-named variables and saved
 * sign-in bodies are all refused, on the rules the other importers already use.
 */
public final class PostmanImporter implements SourceImporter {

    /**
     * A ceiling on how many requests one collection becomes.
     *
     * <p>Enterprise collections run to hundreds of requests across dozens of folders, and a draft of
     * four hundred equally weighted scenarios is not a test anyone will edit into one.
     */
    static final int MAX_SCENARIOS = 200;

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final int MAX_NAME_LENGTH = 63;

    private static final Set<String> METHODS_WITHOUT_BODY = Set.of("GET", "HEAD");

    /** Sent by the client, not chosen by the author. */
    private static final Set<String> CLIENT_NEGOTIATED_HEADERS =
            Set.of("content-length", "host", "connection", "accept-encoding", "user-agent");

    /** Postman's raw-body languages, and what to send them as. */
    private static final Map<String, String> RAW_LANGUAGES = Map.of(
            "json", "application/json",
            "xml", "application/xml",
            "html", "text/html",
            "javascript", "application/javascript",
            "graphql", "application/json",
            "text", "text/plain");

    @Override
    public SourceType sourceType() {
        return SourceType.POSTMAN;
    }

    @Override
    public ImportResult importFrom(String document) {
        if (document == null || document.isBlank()) {
            return ImportResult.nothing(ValidationResult.of(ValidationFinding.error(
                    "import.postman.empty", "/", "No collection was provided.",
                    "Export a collection from Postman as Collection v2.1 and upload it.")));
        }

        JsonNode root;
        try {
            root = JSON.readTree(document);
        } catch (Exception e) {
            return ImportResult.nothing(ValidationResult.of(ValidationFinding.error(
                    "import.postman.unreadable", "/",
                    "This could not be read as a Postman collection.",
                    "In Postman, use the collection's ... menu → Export → Collection v2.1.")));
        }

        if (root == null || !root.isObject()) {
            return ImportResult.nothing(ValidationResult.of(ValidationFinding.error(
                    "import.postman.unreadable", "/",
                    "This could not be read as a Postman collection.", null)));
        }

        if (!root.path("item").isArray() && root.path("requests").isArray()) {
            // Collection v1: a flat 'requests' array with headers as one newline-delimited string.
            // Postman itself converts it, and doing so here would mean maintaining a second reader
            // for a format nothing has exported since 2016.
            return ImportResult.nothing(ValidationResult.of(ValidationFinding.error(
                    "import.postman.legacyFormat", "/",
                    "This is a Collection v1 export, which this platform does not read.",
                    "Open it in Postman and export it again as Collection v2.1.")));
        }

        if (!root.path("item").isArray()) {
            return ImportResult.nothing(ValidationResult.of(ValidationFinding.error(
                    "import.postman.items.missing", "/item",
                    "The collection contains no items, so there are no requests to import.", null)));
        }

        List<ValidationFinding> findings = new ArrayList<>();
        Reading reading = new Reading(root, findings);
        List<ScenarioSpec> scenarios = reading.read();
        reading.reportCollectionLevelFindings();

        return scenarios.isEmpty()
                ? ImportResult.nothing(ValidationResult.of(findings))
                : ImportResult.of(scenarios, ValidationResult.of(findings));
    }

    /** One request, read but not yet weighted: a weight depends on how many others there are. */
    private record Draft(
            String name,
            String method,
            String path,
            Map<String, String> headers,
            Map<String, String> query,
            ScenarioSpec.RequestBody body) {
    }

    /**
     * One pass over one collection.
     *
     * <p>An instance per import, so the counters that aggregate findings are not shared state. As in
     * the other importers, findings that would repeat per request are raised once with a count; the
     * ones naming a specific request — a script, a refused body — are raised per request, because
     * those are the ones somebody has to act on individually.
     */
    private static final class Reading {

        private final JsonNode collection;
        private final List<ValidationFinding> findings;
        private final CollectionVariables variables;
        private final Set<String> usedNames = new LinkedHashSet<>();

        private final Set<String> hosts = new LinkedHashSet<>();
        private final Set<String> dynamicVariables = new LinkedHashSet<>();
        private final Set<String> unknownVariables = new LinkedHashSet<>();

        private int scriptedRequests;
        private int authBlocks;
        private int credentialHeaders;
        private int credentialQueryParameters;
        private int disabledSkipped;
        private int truncated;
        private boolean sawBody;

        private Reading(JsonNode collection, List<ValidationFinding> findings) {
            this.collection = collection;
            this.findings = findings;
            this.variables = CollectionVariables.from(collection);
        }

        private List<ScenarioSpec> read() {
            if (declaresAuth(collection)) {
                authBlocks++;
            }
            countScripts(collection, "the collection");

            List<Draft> drafts = new ArrayList<>();
            walk(collection.path("item"), List.of(), drafts);

            if (drafts.isEmpty()) {
                findings.add(ValidationFinding.error("import.postman.items.none", "/item",
                        "The collection declares folders but no requests that could be imported.",
                        "Check that its requests have a method and a URL."));
                return List.of();
            }
            return withWeights(drafts);
        }

        /**
         * Walks folders and requests in declaration order.
         *
         * <p>An item holding {@code item} is a folder; one holding {@code request} is a request. The
         * folder path becomes the scenario's name prefix, so the structure somebody built in Postman
         * survives into the draft — the same role a page plays in a HAR capture.
         */
        private void walk(JsonNode items, List<String> folders, List<Draft> drafts) {
            for (JsonNode item : items) {
                if (!item.isObject()) {
                    continue;
                }
                String name = item.path("name").asText("");

                if (item.path("item").isArray()) {
                    if (declaresAuth(item)) {
                        authBlocks++;
                    }
                    countScripts(item, "folder '" + name + "'");

                    List<String> nested = new ArrayList<>(folders);
                    if (!name.isBlank()) {
                        nested.add(name);
                    }
                    walk(item.path("item"), nested, drafts);
                    continue;
                }

                if (!item.path("request").isObject() && !item.path("request").isTextual()) {
                    continue;
                }
                if (drafts.size() >= MAX_SCENARIOS) {
                    truncated++;
                    continue;
                }
                readRequest(item, folders, drafts);
            }
        }

        private void readRequest(JsonNode item, List<String> folders, List<Draft> drafts) {
            JsonNode request = item.path("request");
            // A request written as a bare URL string: shorthand for a GET.
            if (request.isTextual()) {
                request = JSON.createObjectNode().put("method", "GET").put("url", request.asText());
            }

            String label = item.path("name").asText("");
            String scenarioName = uniqueName(folders, label, request);

            countScripts(item, "request '" + (label.isBlank() ? scenarioName : label) + "'");
            if (declaresAuth(request)) {
                authBlocks++;
            }

            Optional<PostmanUrl> url = PostmanUrl.read(request.path("url"), variables);
            if (url.isEmpty()) {
                findings.add(ValidationFinding.warning("import.postman.url.missing",
                        "/scenarios/" + scenarioName,
                        "Request '" + label + "' names no URL and was not imported.", null));
                return;
            }

            PostmanUrl address = url.get();
            if (address.host() != null && !address.host().isBlank()) {
                hosts.add(address.host());
            }
            disabledSkipped += address.disabledSkipped();

            reportUnresolved(address, scenarioName);

            String method = request.path("method").asText("GET").toUpperCase(Locale.ROOT);

            drafts.add(new Draft(
                    scenarioName,
                    method,
                    address.path(),
                    readHeaders(request.path("header")),
                    filterQuery(address.query(), scenarioName),
                    readBody(request, method, scenarioName)));
        }

        // ── Variables ────────────────────────────────────────────────────

        /**
         * Reports what could not be substituted, by category and by where it appeared.
         *
         * <p>A placeholder left in a <b>path</b> is an error: sent as it stands every request
         * returns 404, and a run made of 404s reports fast, healthy-looking latency for a target
         * that did no work. Anywhere else it is a warning — usually a missing filter, which changes
         * what is measured without invalidating it.
         */
        private void reportUnresolved(PostmanUrl address, String scenarioName) {
            Set<String> inPath = address.pathResolution().unresolved();
            if (!inPath.isEmpty()) {
                findings.add(ValidationFinding.error("import.postman.path.unresolved",
                        "/scenarios/" + scenarioName + "/path",
                        "The path still contains unresolved placeholder(s): "
                                + String.join(", ", inPath) + ".",
                        "Set the value on the scenario or attach a dataset before running. Sent as "
                                + "it stands, every request returns 404 and the run reports the "
                                + "latency of an error path."));
            }

            dynamicVariables.addAll(address.pathResolution().dynamic());
            dynamicVariables.addAll(address.queryResolution().dynamic());
            unknownVariables.addAll(address.pathResolution().unknown());
            unknownVariables.addAll(address.queryResolution().unknown());

            if (!address.queryResolution().unresolved().isEmpty()) {
                findings.add(ValidationFinding.warning("import.postman.query.unresolved",
                        "/scenarios/" + scenarioName + "/query",
                        "Query parameter(s) still contain unresolved placeholder(s): "
                                + String.join(", ", address.queryResolution().unresolved()) + ".",
                        "Set the values on the scenario or attach a dataset."));
            }
        }

        // ── Scripts ──────────────────────────────────────────────────────

        /**
         * Records that a script exists. It is never read, and never evaluated.
         *
         * <p>CLAUDE.md §23, and the reason is not subtle: the script arrived in a document somebody
         * uploaded over HTTP, and the control plane does not run code that arrived in a document.
         * What it does is not inspected either — a summary of an unevaluated script would be a
         * guess presented as fact.
         */
        private void countScripts(JsonNode owner, String where) {
            JsonNode events = owner.path("event");
            if (!events.isArray()) {
                return;
            }
            for (JsonNode event : events) {
                String listen = event.path("listen").asText("");
                JsonNode exec = event.path("script").path("exec");
                boolean hasCode = exec.isArray() ? !exec.isEmpty()
                        : !event.path("script").path("src").asText("").isBlank();

                if (hasCode && ("prerequest".equals(listen) || "test".equals(listen))) {
                    scriptedRequests++;
                    findings.add(ValidationFinding.warning("import.postman.script.notExecuted",
                            "/scripts",
                            "A '" + listen + "' script on " + where + " was not imported and will "
                                    + "not run.",
                            "Scripts are never executed by this platform. If this one signs in, "
                                    + "sets a token or derives a value the requests depend on, that "
                                    + "work has to be expressed in the specification — as a "
                                    + "secret reference, a dataset, or a scenario of its own."));
                    return;
                }
            }
        }

        private static boolean declaresAuth(JsonNode owner) {
            JsonNode auth = owner.path("auth");
            return auth.isObject() && !"noauth".equals(auth.path("type").asText(""));
        }

        // ── Headers, query and body ──────────────────────────────────────

        private Map<String, String> readHeaders(JsonNode headers) {
            Map<String, String> result = new LinkedHashMap<>();
            if (!headers.isArray()) {
                return result;
            }
            for (JsonNode header : headers) {
                if (header.path("disabled").asBoolean(false)) {
                    disabledSkipped++;
                    continue;
                }
                String name = header.path("key").asText(null);
                if (name == null || name.isBlank()) {
                    continue;
                }
                if (SecretValueDetector.isCredentialHeader(name)) {
                    credentialHeaders++;
                    continue;
                }
                if (CLIENT_NEGOTIATED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                    continue;
                }

                CollectionVariables.Resolution value =
                        variables.resolve(header.path("value").asText(""));
                if (!value.refused().isEmpty()
                        || SecretValueDetector.looksLikeCredential(value.text())) {
                    credentialHeaders++;
                    continue;
                }
                dynamicVariables.addAll(value.dynamic());
                unknownVariables.addAll(value.unknown());
                result.put(name, value.text());
            }
            return result;
        }

        private Map<String, String> filterQuery(Map<String, String> query, String scenarioName) {
            Map<String, String> result = new LinkedHashMap<>();
            for (Map.Entry<String, String> parameter : query.entrySet()) {
                if (SecretValueDetector.isCredentialHeader(parameter.getKey())
                        || SecretValueDetector.looksLikeCredential(parameter.getValue())) {
                    // A query string is written to every access log between the runner and the
                    // target, so this is sharper than the header case.
                    credentialQueryParameters++;
                    continue;
                }
                result.put(parameter.getKey(), parameter.getValue());
            }
            return result;
        }

        /**
         * The body, in whichever of Postman's five modes the collection used.
         *
         * <p>Every mode that survives goes through {@link SensitiveFieldDetector} on the way, for
         * the reason that class exists: a collection's saved sign-in request holds a working
         * password, and no check on the <em>value</em> would recognise it.
         */
        private ScenarioSpec.RequestBody readBody(JsonNode request, String method,
                String scenarioName) {
            JsonNode body = request.path("body");
            if (!body.isObject() || body.path("disabled").asBoolean(false)) {
                return null;
            }

            String mode = body.path("mode").asText("");
            String where = "/scenarios/" + scenarioName + "/body";

            if (METHODS_WITHOUT_BODY.contains(method)) {
                if (!mode.isBlank()) {
                    findings.add(ValidationFinding.warning(
                            "import.postman.body.onMethodWithoutBody", where,
                            "The collection declares a request body for a " + method + ", which was "
                                    + "not imported.",
                            "Many servers and proxies drop a body on " + method + ", so the test "
                                    + "would measure a different request than the one written."));
                }
                return null;
            }

            String content;
            String contentType;

            switch (mode) {
                case "raw" -> {
                    content = variables.resolve(body.path("raw").asText("")).text();
                    contentType = RAW_LANGUAGES.getOrDefault(
                            body.path("options").path("raw").path("language").asText("")
                                    .toLowerCase(Locale.ROOT),
                            "application/json");
                }
                case "urlencoded" -> {
                    content = encodeForm(body.path("urlencoded"), where);
                    contentType = "application/x-www-form-urlencoded";
                }
                case "graphql" -> {
                    content = graphql(body.path("graphql"));
                    contentType = "application/json";
                }
                case "formdata", "file" -> {
                    findings.add(ValidationFinding.warning("import.postman.body.unsupported", where,
                            "This request sends " + ("file".equals(mode) ? "a file" : "form-data")
                                    + ", which was not imported.",
                            "A collection stores a reference to a file on the author's machine, not "
                                    + "its contents, so there is nothing to send. Write the body on "
                                    + "the scenario instead."));
                    return null;
                }
                default -> {
                    return null;
                }
            }

            if (content == null || content.isBlank()) {
                return null;
            }

            SensitiveFieldDetector.Scan scan = SensitiveFieldDetector.scan(contentType, content);
            switch (scan.verdict()) {
                case CREDENTIAL -> {
                    findings.add(ValidationFinding.warning("import.postman.body.credential", where,
                            scan.field() != null
                                    ? "The request body contains a field named '" + scan.field()
                                            + "', so it was not imported."
                                    : "The request body appears to contain a credential, so it was "
                                            + "not imported.",
                            "A saved collection keeps whatever value made the request work. "
                                    + "Declare authentication under spec.authentication as a secret "
                                    + "reference, and supply the rest of the body from a dataset."));
                    return null;
                }
                case PERSONAL_DATA -> {
                    findings.add(ValidationFinding.warning("import.postman.body.personalData", where,
                            "The request body contains a field named '" + scan.field()
                                    + "', so it was not imported.",
                            "Supply this from a dataset of test data instead."));
                    return null;
                }
                case UNSUPPORTED -> {
                    return null;
                }
                default -> {
                    sawBody = true;
                    return new ScenarioSpec.RequestBody(contentType, content);
                }
            }
        }

        /** Postman's url-encoded mode: a list of pairs, some of them switched off. */
        private String encodeForm(JsonNode parameters, String where) {
            if (!parameters.isArray()) {
                return "";
            }
            List<String> names = new ArrayList<>();
            StringBuilder encoded = new StringBuilder();

            for (JsonNode parameter : parameters) {
                if (parameter.path("disabled").asBoolean(false)) {
                    disabledSkipped++;
                    continue;
                }
                String key = parameter.path("key").asText(null);
                if (key == null || key.isBlank()) {
                    continue;
                }
                names.add(key);
                if (encoded.length() > 0) {
                    encoded.append('&');
                }
                encoded.append(URLEncoder.encode(key, StandardCharsets.UTF_8)).append('=')
                        .append(URLEncoder.encode(
                                variables.resolve(parameter.path("value").asText("")).text(),
                                StandardCharsets.UTF_8));
            }

            // Checked on the names directly rather than on the encoded text: percent-encoding would
            // hide 'client_secret' from a scan of the serialized body.
            if (!SensitiveFieldDetector.scanNames(names).isSafe()) {
                findings.add(ValidationFinding.warning("import.postman.body.credential", where,
                        "The form body contains a field named '"
                                + SensitiveFieldDetector.scanNames(names).field()
                                + "', so it was not imported.",
                        "Declare authentication under spec.authentication as a secret reference."));
                return "";
            }
            return encoded.toString();
        }

        /** GraphQL is an ordinary JSON body once it is assembled. */
        private String graphql(JsonNode graphql) {
            String query = variables.resolve(graphql.path("query").asText("")).text();
            if (query.isBlank()) {
                return "";
            }
            ObjectNode body = JSON.createObjectNode();
            body.put("query", query);

            String rawVariables = graphql.path("variables").isTextual()
                    ? variables.resolve(graphql.path("variables").asText()).text()
                    : null;
            if (rawVariables != null && !rawVariables.isBlank()) {
                try {
                    body.set("variables", JSON.readTree(rawVariables));
                } catch (Exception e) {
                    // Malformed variables in the collection. The query alone is still a valid
                    // request, and inventing the variables is not this importer's business.
                    body.remove("variables");
                }
            } else if (graphql.path("variables").isObject()) {
                body.set("variables", graphql.path("variables"));
            }

            try {
                return JSON.writeValueAsString(body);
            } catch (Exception e) {
                return "";
            }
        }

        // ── Naming and weighting ─────────────────────────────────────────

        /**
         * A scenario name: the folders it sits in, then what its author called it.
         *
         * <p>Preferred over anything derived from the path, because a collection is the one source
         * where somebody already wrote down what each request is <em>for</em>. Names reach artifact
         * object keys and Kubernetes labels, so they are reshaped to lowercase-with-hyphens and
         * capped at 63 characters.
         */
        private String uniqueName(List<String> folders, String label, JsonNode request) {
            List<String> parts = new ArrayList<>(folders);
            parts.add(label.isBlank()
                    ? request.path("method").asText("request").toLowerCase(Locale.ROOT)
                    : label);

            String base = kebab(String.join("-", parts));
            if (base.isEmpty()) {
                base = "request";
            }

            String candidate = truncate(base, MAX_NAME_LENGTH);
            for (int suffix = 2; !usedNames.add(candidate); suffix++) {
                String tail = "-" + suffix;
                candidate = truncate(base, MAX_NAME_LENGTH - tail.length()) + tail;
            }
            return candidate;
        }

        private static String kebab(String value) {
            String hyphenated = value.replaceAll("([a-z0-9])([A-Z])", "$1-$2")
                    .replaceAll("[^A-Za-z0-9]+", "-")
                    .toLowerCase(Locale.ROOT);
            return hyphenated.replaceAll("^-+", "").replaceAll("-+$", "")
                    .replaceAll("-{2,}", "-");
        }

        private static String truncate(String value, int limit) {
            String cut = value.length() <= limit ? value : value.substring(0, limit);
            return cut.replaceAll("-+$", "");
        }

        /**
         * Equal shares summing to 100.
         *
         * <p>A collection says nothing about how much traffic each request receives — folder order
         * is how somebody organised their work, not a traffic mix — so the weights are visibly a
         * starting point rather than a plausible-looking claim. This is the same choice the OpenAPI
         * importer makes, and the opposite of the HAR one, which has real frequencies to use.
         */
        private List<ScenarioSpec> withWeights(List<Draft> drafts) {
            int count = drafts.size();
            int base = count > 100 ? 1 : 100 / count;
            int remainder = count > 100 ? 0 : 100 % count;

            findings.add(ValidationFinding.info("import.postman.weights.equal", "/scenarios",
                    "Every request was imported with an equal weight. A collection describes what "
                            + "an API can do, not how much traffic each request receives, so set "
                            + "the mix from what you know about production before drawing "
                            + "conclusions from a run."));

            List<ScenarioSpec> scenarios = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                Draft draft = drafts.get(i);
                scenarios.add(new ScenarioSpec(
                        draft.name(),
                        base + (i < remainder ? 1 : 0),
                        null,
                        draft.method(),
                        draft.path(),
                        draft.headers(),
                        draft.query(),
                        draft.body()));
            }
            return scenarios;
        }

        // ── Findings ─────────────────────────────────────────────────────

        /** Everything that belongs to the collection rather than to one request. */
        private void reportCollectionLevelFindings() {
            if (scriptedRequests > 0) {
                findings.add(ValidationFinding.warning("import.postman.scripts.summary", "/scripts",
                        scriptedRequests + " script(s) in this collection were not imported and are "
                                + "never executed by this platform.",
                        "If any of them performs a sign-in or derives a value the requests depend "
                                + "on, the collection will import cleanly and every request will "
                                + "still fail at run time. Check what they do before running."));
            }
            if (authBlocks > 0) {
                findings.add(ValidationFinding.warning("import.postman.auth.declared", "/",
                        authBlocks + " authentication block(s) were declared in this collection and "
                                + "none were imported.",
                        "Declare authentication under spec.authentication as a secret reference. "
                                + "The runner resolves it at execution time, so it never enters the "
                                + "specification."));
            }
            if (!variables.refusedNames().isEmpty()) {
                findings.add(ValidationFinding.warning("import.postman.variable.credential", "/",
                        variables.refusedNames().size() + " collection variable(s) hold credentials "
                                + "and were not substituted: "
                                + String.join(", ", variables.refusedNames()) + ".",
                        "An exported collection carries its variable values with it, so these are "
                                + "live. Rotate them if the export has been shared, and declare "
                                + "them under spec.authentication as secret references."));
            }
            if (credentialHeaders > 0) {
                findings.add(ValidationFinding.warning("import.postman.header.credential", "/",
                        credentialHeaders + " header(s) carrying authentication were not imported.",
                        "Declare them under spec.authentication as secret references."));
            }
            if (credentialQueryParameters > 0) {
                findings.add(ValidationFinding.warning("import.postman.query.credential", "/",
                        credentialQueryParameters + " query parameter(s) looked like credentials "
                                + "and were not imported.",
                        "A query string is written to every access log between the runner and the "
                                + "target."));
            }
            if (!unknownVariables.isEmpty()) {
                findings.add(ValidationFinding.warning("import.postman.variable.unknown", "/",
                        "The collection uses variable(s) it states no value for: "
                                + String.join(", ", unknownVariables) + ".",
                        "These usually live in a Postman environment, which is exported separately "
                                + "and was not uploaded. Set them on the scenarios or attach a "
                                + "dataset."));
            }
            if (!dynamicVariables.isEmpty()) {
                findings.add(ValidationFinding.warning("import.postman.variable.dynamic", "/",
                        "The collection uses Postman's generated variable(s): "
                                + String.join(", ", dynamicVariables) + ". This platform does not "
                                + "evaluate them.",
                        "A dataset is the equivalent: it supplies a different value per iteration, "
                                + "and unlike a generated one it can be data the target recognises."));
            }

            if (!hosts.isEmpty()) {
                findings.add(ValidationFinding.info("import.postman.host.dropped", "/",
                        "The host(s) " + String.join(", ", hosts) + " were not imported; only paths "
                                + "were. A run's traffic goes to the target registered for its "
                                + "application, so check that this test's metadata names the "
                                + "application you intend."));
            }
            if (disabledSkipped > 0) {
                findings.add(ValidationFinding.info("import.postman.disabled.skipped", "/",
                        disabledSkipped + " header(s), query parameter(s) or form field(s) were "
                                + "switched off in the collection and were not imported."));
            }
            if (sawBody) {
                findings.add(ValidationFinding.info("import.postman.body.static", "/",
                        "Imported request bodies are fixed, so every virtual user sends the same "
                                + "payload. For a test that writes data, vary it with a dataset — "
                                + "otherwise the run measures the target's behaviour on one "
                                + "repeated request."));
            }
            if (truncated > 0) {
                findings.add(ValidationFinding.warning("import.postman.items.truncated", "/item",
                        truncated + " further request(s) were not imported; the first "
                                + MAX_SCENARIOS + " were kept.",
                        "Import the folders you mean to test. A test covering every request in a "
                                + "collection does not resemble any real traffic."));
            }
        }
    }
}
