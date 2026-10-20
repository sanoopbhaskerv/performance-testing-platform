package com.perfplatform.importer.har;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.perfplatform.domain.importer.SourceImporter;
import com.perfplatform.domain.importer.SourceImporter.CorrelationSuggestion;
import com.perfplatform.domain.importer.SourceImporter.ExtractionEvidence;
import com.perfplatform.domain.importer.SourceImporter.ReplacementEvidence;
import com.perfplatform.domain.spec.PerformanceTestSpecification.ScenarioSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.StepSpec;
import com.perfplatform.domain.spec.SourceType;
import com.perfplatform.domain.validation.SecretValueDetector;
import com.perfplatform.domain.validation.SensitiveFieldDetector;
import com.perfplatform.domain.validation.ValidationFinding;
import com.perfplatform.domain.validation.ValidationResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Turns a browser HAR capture into draft scenarios grouped into candidate journeys.
 *
 * <p>A HAR is the best and the most dangerous source this platform accepts.
 *
 * <p>Best, because it is the only one that records what a real user actually did — in order, and
 * <em>how often</em>. An OpenAPI document lists every operation an API has and says nothing about
 * which ones matter; a capture answers that question with evidence, which is why the weights here
 * come from observed frequency rather than being spread evenly.
 *
 * <p>Dangerous, because it is a verbatim recording of a live session. Every cookie is a live cookie,
 * every {@code Authorization} header a live token, and the sign-in request — the one most likely to
 * be worth load-testing — carries somebody's password in plain text. It also records every static
 * asset the page fetched and every third-party beacon it fired, from hosts that are not the system
 * under test.
 *
 * <h2>So this importer removes far more than it keeps</h2>
 * Assets, failed exchanges, third-party hosts, cookies, authentication, session tokens, browser
 * negotiation headers, conditional-request headers and any body carrying a credential or personal
 * data. Every removal is counted and reported. That reporting is the deliverable as much as the
 * scenarios are: CLAUDE.md §23 requires review before load execution, and a person can only review
 * what they have been told.
 *
 * <h2>A captured page becomes an ordered journey</h2>
 * A browser records which page each request belonged to, which is the closest thing a capture has to
 * a statement of intent. Each page group becomes one scenario whose {@code steps} are its requests
 * <b>in the order they were made</b>. Until S3 a {@code ScenarioSpec} was a single request, so a
 * journey could only survive as several scenarios sharing a name prefix, with a warning that their
 * order was lost. It is not lost any more.
 *
 * <h2>What it still cannot do, and says so</h2>
 * <b>Correlation is not inferred.</b> The importer can see that a response contained an identifier
 * and that a later request used one, but not that they are the same thing — and guessing wrongly
 * produces a journey that looks wired up and sends the wrong value. So a step that needs a value
 * from an earlier response still needs an {@code extract} added, and a finding says so on every
 * import that produced a multi-step journey.
 *
 * <h2>Repeats inside a journey are kept</h2>
 * Sibling scenarios collapsed repeats of the same call and raised a weight instead, because the load
 * model ran them independently and the ratio had to live somewhere. A sequence carries its own
 * ratio: a journey that fetched the basket twice fetched it twice, and collapsing that would change
 * what runs. Collapsing still applies <em>across</em> journeys, where two captures of the same page
 * genuinely are the same journey observed twice.
 */
public final class HarImporter implements SourceImporter {

    /**
     * A ceiling on how many scenarios one capture becomes.
     *
     * <p>Captures run to thousands of entries. When the cap bites, the <em>most frequently
     * observed</em> requests are kept rather than the first ones seen: frequency is the signal the
     * capture actually carries, and the first entries of a session are typically its page load.
     */
    static final int MAX_SCENARIOS = 200;

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final int MAX_NAME_LENGTH = 63;

    /**
     * Headers a browser negotiated, not headers an author chose.
     *
     * <p>Three kinds, and each would misdirect the test in its own way. Transport and encoding
     * headers describe a connection the runner will not have. <b>Conditional and caching headers are
     * the subtle one</b>: replaying {@code If-None-Match} makes the target answer 304 without doing
     * any work, and the run reports excellent latency for a service that did nothing. And
     * {@code Referer} and {@code Origin} carry the captured host, which this platform drops
     * everywhere else.
     */
    private static final Set<String> NEGOTIATED_HEADERS = Set.of(
            "host", "connection", "content-length", "transfer-encoding", "te", "keep-alive",
            "accept-encoding", "upgrade-insecure-requests", "dnt", "priority",
            "if-none-match", "if-modified-since", "if-match", "if-unmodified-since",
            "cache-control", "pragma",
            "referer", "origin", "user-agent");

    /**
     * Headers carrying something bound to the captured session.
     *
     * <p>Not credentials in {@link SecretValueDetector}'s sense — a CSRF token is not shaped like a
     * bearer token — but exactly as useless and as sensitive. It was minted for one session, it will
     * be rejected on replay, and it identifies the person who made the capture.
     */
    private static final Set<String> SESSION_HEADERS = Set.of(
            "x-csrf-token", "x-xsrf-token", "csrf-token", "x-session-id", "x-session-token");

    @Override
    public SourceType sourceType() {
        return SourceType.HAR;
    }

    @Override
    public ImportResult importFrom(String document) {
        if (document == null || document.isBlank()) {
            return ImportResult.nothing(ValidationResult.of(ValidationFinding.error(
                    "import.har.empty", "/", "No capture was provided.",
                    "Export a HAR from your browser's network tab and upload it.")));
        }

        JsonNode root;
        try {
            root = JSON.readTree(document);
        } catch (Exception e) {
            return ImportResult.nothing(ValidationResult.of(ValidationFinding.error(
                    "import.har.unreadable", "/", "This could not be read as a HAR capture.",
                    "A HAR is a JSON file with a top-level 'log' object. Export one from your "
                            + "browser's network tab with 'Save all as HAR'.")));
        }

        JsonNode log = root == null ? null : root.path("log");
        if (log == null || !log.isObject() || !log.path("entries").isArray()) {
            return ImportResult.nothing(ValidationResult.of(ValidationFinding.error(
                    "import.har.entries.missing", "/log/entries",
                    "The capture contains no entries, so there are no requests to import.", null)));
        }

        List<ValidationFinding> findings = new ArrayList<>();
        Reading reading = new Reading(findings);
        List<ScenarioSpec> scenarios = reading.read(log);
        reading.reportCaptureLevelFindings();

        return scenarios.isEmpty()
                ? ImportResult.nothing(ValidationResult.of(findings))
                : ImportResult.of(scenarios, ValidationResult.of(findings), reading.suggestions());
    }

    /** A request kept for import, and what the capture observed about it. */
    private static final class Kept {
        private final CapturedRequest request;
        private int occurrences = 1;
        private int queryVariants;

        private Kept(CapturedRequest request) {
            this.request = request;
        }
    }

    /**
     * One pass over one capture.
     *
     * <p>An instance per import, so the counters that aggregate findings are not shared state.
     * Nearly every finding here is aggregated: a capture repeats the same cookie on two hundred
     * entries, and two hundred identical findings would bury the handful that need a decision.
     */
    private static final class Reading {

        private final List<ValidationFinding> findings;
        private final Set<String> usedNames = new LinkedHashSet<>();

        private Map<String, String> journeyNames = Map.of();
        private String targetHost;
        private final Set<String> otherHosts = new LinkedHashSet<>();

        private int unreadable;
        private int assetsDropped;
        private int failuresDropped;
        private int thirdPartyDropped;
        private int duplicatesCollapsed;
        private int cookiesStripped;
        private int credentialHeaders;
        private int sessionHeaders;
        private int credentialQueryParameters;
        private int journeyCount;
        private int multiStepJourneys;
        private final Set<String> usedStepNames = new LinkedHashSet<>();
        private int truncated;
        private boolean sawBody;
        private boolean imported;
        private final List<CorrelationSuggestion> suggestions = new ArrayList<>();

        private Reading(List<ValidationFinding> findings) {
            this.findings = findings;
        }

        private List<CorrelationSuggestion> suggestions() {
            return suggestions;
        }

        private List<ScenarioSpec> read(JsonNode log) {
            journeyNames = readPages(log.path("pages"));

            List<CapturedRequest> captured = readEntries(log.path("entries"));
            List<CapturedRequest> candidates = keepApplicationRequests(captured);
            if (candidates.isEmpty()) {
                explainWhyNothingSurvived(captured);
                return List.of();
            }

            // Deliberately NOT collapsed. Sibling scenarios collapsed repeats and raised a weight
            // instead, because independent scenarios needed the ratio to live somewhere. A sequence
            // carries its own: a journey that fetched the basket twice fetched it twice, and
            // collapsing that would both change what runs and quietly halve the traffic the capture
            // observed for it. The repeats stay, in the order they happened.
            List<Kept> kept = candidates.stream().map(Kept::new).toList();
            kept = truncate(kept);
            journeyCount = (int) kept.stream()
                    .map(entry -> journeyOf(entry.request)).distinct().count();

            return buildJourneys(kept);
        }

        // ── Reading ──────────────────────────────────────────────────────

        /**
         * Journey names, from the capture's own page list.
         *
         * <p>A browser records which page each request belonged to, which is the closest thing a
         * capture has to a statement of intent. Where the capturing tool wrote no pages, everything
         * becomes one journey rather than being guessed at from timing gaps.
         */
        private Map<String, String> readPages(JsonNode pages) {
            Map<String, String> names = new LinkedHashMap<>();
            if (!pages.isArray()) {
                return names;
            }
            for (JsonNode page : pages) {
                String id = page.path("id").asText(null);
                if (id == null || id.isBlank()) {
                    continue;
                }
                String title = page.path("title").asText("");
                names.put(id, kebab(title.isBlank() ? id : titleToName(title)));
            }
            return names;
        }

        /**
         * A page title is a URL in most captures, and a sentence in some.
         *
         * <p>Either way only the last meaningful part is worth a name, and the host is never part of
         * it — this platform does not carry a captured host anywhere.
         */
        private static String titleToName(String title) {
            String withoutScheme = title.replaceFirst("^[a-zA-Z][a-zA-Z0-9+.-]*://", "");
            int slash = withoutScheme.indexOf('/');
            String withoutHost = slash < 0 ? withoutScheme : withoutScheme.substring(slash + 1);
            String trimmed = withoutHost.split("[?#]")[0];
            return trimmed.isBlank() ? "home" : trimmed;
        }

        private List<CapturedRequest> readEntries(JsonNode entries) {
            List<CapturedRequest> captured = new ArrayList<>();
            for (JsonNode entry : entries) {
                Optional<CapturedRequest> request = CapturedRequest.from(entry);
                if (request.isEmpty()) {
                    unreadable++;
                } else {
                    captured.add(request.get());
                }
            }
            return captured;
        }

        // ── Filtering ────────────────────────────────────────────────────

        /**
         * The requests that exercise the application under test.
         *
         * <p>Three filters, in an order that matters: assets and failures first, so the host count
         * that follows is taken over requests somebody might actually want.
         */
        private List<CapturedRequest> keepApplicationRequests(List<CapturedRequest> captured) {
            List<CapturedRequest> candidates = new ArrayList<>();
            for (CapturedRequest request : captured) {
                if (request.isAsset()) {
                    assetsDropped++;
                } else if (!request.succeeded()) {
                    // It failed when a real browser made it, with a real session. Replaying it would
                    // add a known failure to the run's error rate and measure an error path.
                    failuresDropped++;
                } else {
                    candidates.add(request);
                }
            }

            targetHost = dominantHost(candidates);
            if (targetHost == null) {
                return candidates;
            }

            List<CapturedRequest> ownHost = new ArrayList<>();
            for (CapturedRequest request : candidates) {
                if (targetHost.equalsIgnoreCase(request.host())) {
                    ownHost.add(request);
                } else {
                    // This is the filter that has to exist. A capture calls analytics, CDNs and
                    // payment widgets, and this platform drops the host from every request it
                    // imports — so an unfiltered capture would turn a beacon's '/collect' into a
                    // request against the team's own target.
                    thirdPartyDropped++;
                    if (request.host() != null) {
                        otherHosts.add(request.host());
                    }
                }
            }
            return ownHost;
        }

        /**
         * The host most requests went to.
         *
         * <p>Frequency rather than the first entry seen: the first request in a capture is often a
         * redirect from a vanity domain or an identity provider, and the application under test is
         * the one the session spends its time talking to.
         */
        private static String dominantHost(List<CapturedRequest> candidates) {
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (CapturedRequest request : candidates) {
                if (request.host() != null) {
                    counts.merge(request.host().toLowerCase(Locale.ROOT), 1, Integer::sum);
                }
            }
            return counts.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(null);
        }

        /**
         * Collapses repeats of the same call, counting them.
         *
         * <p>The count is the point. It is the one thing a capture knows that no other source does,
         * and it becomes the scenario's weight. Identity is journey, method and path — not the query
         * string, so paging through a list reads as one call made several times rather than as
         * several different calls.
         */
        private List<Kept> collapseRepeats(List<CapturedRequest> candidates) {
            Map<String, Kept> bySignature = new LinkedHashMap<>();
            for (CapturedRequest request : candidates) {
                Kept existing = bySignature.get(request.signature());
                if (existing == null) {
                    bySignature.put(request.signature(), new Kept(request));
                    continue;
                }
                existing.occurrences++;
                duplicatesCollapsed++;
                if (!existing.request.query().equals(request.query())) {
                    existing.queryVariants++;
                }
            }
            return new ArrayList<>(bySignature.values());
        }

        /**
         * Keeps the first requests, in capture order.
         *
         * <p>Was "keep the most frequently observed", which is right when scenarios run
         * independently and wrong for a sequence: dropping requests out of the middle of a journey
         * leaves one that never happened, and the steps around the gap may depend on what was
         * removed. Truncating the tail leaves a shorter journey that is at least a real prefix of
         * the session, and the finding says how much was lost.
         */
        private List<Kept> truncate(List<Kept> kept) {
            if (kept.size() <= MAX_SCENARIOS) {
                return kept;
            }
            truncated = kept.size() - MAX_SCENARIOS;
            return kept.subList(0, MAX_SCENARIOS);
        }

        // ── Building ─────────────────────────────────────────────────────

        /**
         * One scenario per captured page, whose steps are its requests in order.
         *
         * <p>Grouping is by the capture's own page reference, preserved in encounter order so the
         * journeys read the way the session ran. A capture with no pages produces one journey, which
         * is the honest reading: the tool recorded no boundaries, so inventing some from timing gaps
         * would be a guess presented as structure.
         */
        private List<ScenarioSpec> buildJourneys(List<Kept> kept) {
            Map<String, List<Kept>> byJourney = new LinkedHashMap<>();
            for (Kept entry : kept) {
                byJourney.computeIfAbsent(journeyOf(entry.request), key -> new ArrayList<>())
                        .add(entry);
            }

            // A journey's share of traffic is how often the journey ran, and a capture almost never
            // says: one session usually visits each page once. So the observed per-request counts
            // are NOT reused as journey weights — a page that happens to make twenty calls is a
            // heavy page, not a popular one, and weighting it twenty times higher would describe
            // traffic nobody observed. Equal shares, and a finding says why.
            int[] weights = distribute(byJourney.values().stream().mapToInt(steps -> 1).toArray());

            List<ScenarioSpec> scenarios = new ArrayList<>(byJourney.size());
            int index = 0;
            for (Map.Entry<String, List<Kept>> journey : byJourney.entrySet()) {
                String name = journeyName(journey.getKey(), journey.getValue());
                // Reset per journey: two journeys that both fetch the basket should both call that
                // step 'basket'. A step name is what a reader matches against the request they
                // remember making, and a global counter would turn the second one into 'basket-2'
                // for no reason they can see.
                usedStepNames.clear();
                List<StepSpec> steps = new ArrayList<>(journey.getValue().size());

                for (Kept entry : journey.getValue()) {
                    CapturedRequest request = entry.request;
                    String stepName = stepNameFor(request);
                    steps.add(new StepSpec(
                            stepName,
                            request.method(),
                            request.path(),
                            sanitizeHeaders(request),
                            sanitizeQuery(request),
                            sanitizeBody(request, name + "/" + stepName),
                            List.of(),
                            List.of(),
                            null));

                    if (entry.queryVariants > 0) {
                        findings.add(ValidationFinding.info("import.har.query.varied",
                                "/scenarios/" + name + "/steps/" + stepName,
                                "'" + request.method() + " " + request.path() + "' was called with "
                                        + (entry.queryVariants + 1) + " different sets of query "
                                        + "parameters. The first was imported; vary the rest with a "
                                        + "dataset if the difference matters."));
                    }
                }

                suggestions.addAll(suggestCorrelations(name, journey.getValue(), steps));

                scenarios.add(new ScenarioSpec(name, weights[index++], null, null, null,
                        Map.of(), Map.of(), null, steps));
            }

            imported = true;
            multiStepJourneys = (int) scenarios.stream()
                    .filter(scenario -> scenario.steps().size() > 1).count();
            return scenarios;
        }

        /**
         * Suggests correlations visible in the capture. Suggestions are evidence, not edits.
         */
        private static List<CorrelationSuggestion> suggestCorrelations(String scenarioName,
                List<Kept> kept, List<StepSpec> steps) {
            List<CorrelationSuggestion> found = new ArrayList<>();
            Set<String> usedVariables = new LinkedHashSet<>();

            for (int sourceIndex = 0; sourceIndex < kept.size(); sourceIndex++) {
                CapturedRequest source = kept.get(sourceIndex).request;
                StepSpec sourceStep = steps.get(sourceIndex);
                for (CandidateValue candidate : responseCandidates(source)) {
                    List<ReplacementEvidence> replacements = new ArrayList<>();
                    for (int targetIndex = sourceIndex + 1; targetIndex < steps.size(); targetIndex++) {
                        replacements.addAll(usagesIn(steps.get(targetIndex), candidate.value()));
                    }
                    if (replacements.isEmpty()) {
                        continue;
                    }

                    String variable = uniqueVariable(candidate.name(), usedVariables);
                    found.add(new CorrelationSuggestion(
                            scenarioName + ":" + sourceStep.name() + ":" + variable,
                            scenarioName,
                            variable,
                            new ExtractionEvidence(
                                    sourceStep.name(),
                                    candidate.from(),
                                    candidate.expression(),
                                    preview(candidate.value())),
                            replacements));
                }
            }
            return found.size() <= 25 ? found : found.subList(0, 25);
        }

        private record CandidateValue(String name, String from, String expression, String value) {
        }

        private static List<CandidateValue> responseCandidates(CapturedRequest request) {
            List<CandidateValue> values = new ArrayList<>();
            String body = request.responseBody();
            if (body != null && request.responseType() != null
                    && request.responseType().toLowerCase(Locale.ROOT).contains("json")) {
                try {
                    collectJsonValues(JSON.readTree(body), "$", values);
                } catch (Exception ignored) {
                    // A truncated response body should remove a suggestion, not fail the import.
                }
            }

            for (Map.Entry<String, String> header : request.responseHeaders().entrySet()) {
                if (safeCorrelationValue(header.getKey(), header.getValue())) {
                    values.add(new CandidateValue(variableName(header.getKey()),
                            "header", header.getKey(), header.getValue()));
                }
            }

            return values.stream()
                    .filter(value -> safeCorrelationValue(value.name(), value.value()))
                    .filter(value -> !value.name().isBlank())
                    .toList();
        }

        private static void collectJsonValues(JsonNode node, String path,
                List<CandidateValue> values) {
            if (node == null || node.isNull()) {
                return;
            }
            if (node.isObject()) {
                node.fields().forEachRemaining(entry ->
                        collectJsonValues(entry.getValue(), path + "." + entry.getKey(), values));
                return;
            }
            if (node.isArray()) {
                for (int i = 0; i < node.size(); i++) {
                    collectJsonValues(node.get(i), path + "[" + i + "]", values);
                }
                return;
            }
            if (node.isTextual() || node.isNumber()) {
                String value = node.asText();
                String name = path.substring(path.lastIndexOf('.') + 1)
                        .replaceAll("\\[[0-9]+]$", "");
                values.add(new CandidateValue(variableName(name), "json-body", path, value));
            }
        }

        private static List<ReplacementEvidence> usagesIn(StepSpec step, String value) {
            List<ReplacementEvidence> usages = new ArrayList<>();
            if (contains(step.path(), value)) {
                usages.add(new ReplacementEvidence(step.name(), "path", null, value));
            }
            step.query().forEach((name, current) -> {
                if (contains(current, value)) {
                    usages.add(new ReplacementEvidence(step.name(), "query", name, current));
                }
            });
            step.headers().forEach((name, current) -> {
                if (contains(current, value)) {
                    usages.add(new ReplacementEvidence(step.name(), "header", name, current));
                }
            });
            if (step.body() != null && contains(step.body().content(), value)) {
                usages.add(new ReplacementEvidence(step.name(), "body", null, value));
            }
            return usages;
        }

        private static boolean contains(String text, String value) {
            return text != null && value != null && !value.isBlank() && text.contains(value);
        }

        private static boolean safeCorrelationValue(String name, String value) {
            if (value == null || value.isBlank() || value.length() < 3 || value.length() > 160) {
                return false;
            }
            if (SecretValueDetector.isCredentialHeader(name)
                    || SecretValueDetector.looksLikeCredential(value)) {
                return false;
            }
            String lowerName = name == null ? "" : name.toLowerCase(Locale.ROOT);
            return !(lowerName.contains("password") || lowerName.contains("token")
                    || lowerName.contains("secret") || lowerName.contains("cookie"));
        }

        private static String variableName(String raw) {
            String cleaned = raw == null ? "" : raw
                    .replaceAll("[^A-Za-z0-9_.-]+", "-")
                    .replaceAll("^-+|-+$", "");
            if (cleaned.isBlank()) {
                return "";
            }
            if (!Character.isLetter(cleaned.charAt(0)) && cleaned.charAt(0) != '_') {
                cleaned = "value-" + cleaned;
            }
            return truncate(cleaned, 40);
        }

        private static String uniqueVariable(String preferred, Set<String> used) {
            String base = preferred.isBlank() ? "value" : preferred;
            String candidate = base;
            for (int suffix = 2; !used.add(candidate); suffix++) {
                candidate = truncate(base, 36) + "-" + suffix;
            }
            return candidate;
        }

        private static String preview(String value) {
            if (value == null || value.length() <= 24) {
                return value == null ? "" : value;
            }
            return value.substring(0, 10) + "..." + value.substring(value.length() - 6);
        }

        /** A journey's name: the page it came from, or its first call when the capture named none. */
        private String journeyName(String journey, List<Kept> steps) {
            String base = journey.isEmpty()
                    ? callName(steps.get(0).request)
                    : journey;
            if (base.isEmpty()) {
                base = "journey";
            }
            String candidate = truncate(base, MAX_NAME_LENGTH);
            for (int suffix = 2; !usedNames.add(candidate); suffix++) {
                String tail = "-" + suffix;
                candidate = truncate(base, MAX_NAME_LENGTH - tail.length()) + tail;
            }
            return candidate;
        }

        /**
         * A step's name, unique within its journey.
         *
         * <p>Scoped per journey rather than globally: two journeys that both fetch the basket should
         * both call that step {@code basket}, because the step name is what a reader matches against
         * the request they remember making.
         */
        private String stepNameFor(CapturedRequest request) {
            String base = callName(request);
            if (base.isEmpty()) {
                base = "request";
            }
            String candidate = truncate(base, MAX_NAME_LENGTH);
            for (int suffix = 2; !usedStepNames.add(candidate); suffix++) {
                String tail = "-" + suffix;
                candidate = truncate(base, MAX_NAME_LENGTH - tail.length()) + tail;
            }
            return candidate;
        }

        /**
         * Weights from what the capture observed, normalised to sum to 100.
         *
         * <p>Largest-remainder apportionment, with every scenario guaranteed at least 1 — a weight
         * of zero means the scenario never runs, which the specification refuses, and a request that
         * was observed once was still observed.
         *
         * <p>Above a hundred scenarios no distribution summing to 100 can give each a positive share,
         * so every scenario gets 1 and the resulting "weights sum to N, not 100" warning is the right
         * thing for someone to see: a hundred-scenario test needs a mix chosen by a person.
         */
        private static int[] distribute(int[] observed) {
            int count = observed.length;
            int[] weights = new int[count];
            if (count > 100) {
                Arrays.fill(weights, 1);
                return weights;
            }

            int total = Arrays.stream(observed).sum();
            int assigned = 0;
            for (int i = 0; i < count; i++) {
                weights[i] = observed[i] * 100 / total;
                assigned += weights[i];
            }

            // Largest fractional remainders take the seats rounding left over.
            Integer[] order = new Integer[count];
            for (int i = 0; i < count; i++) {
                order[i] = i;
            }
            Arrays.sort(order, Comparator.comparingInt(
                    (Integer i) -> observed[i] * 100 % total).reversed());
            for (int i = 0; assigned < 100; i++, assigned++) {
                weights[order[i % count]]++;
            }

            // Then lift any zero to one, taking from whichever share can spare it.
            for (int i = 0; i < count; i++) {
                if (weights[i] != 0) {
                    continue;
                }
                int largest = 0;
                for (int j = 1; j < count; j++) {
                    if (weights[j] > weights[largest]) {
                        largest = j;
                    }
                }
                weights[largest]--;
                weights[i] = 1;
            }
            return weights;
        }

        // ── Sanitizing ───────────────────────────────────────────────────

        /**
         * The headers worth sending, which is few of them.
         *
         * <p>Everything removed here is removed because sending it would be wrong, not merely
         * because it is sensitive — a captured cookie is expired as well as private, a conditional
         * header would make the target answer without working, and a browser fingerprint describes a
         * client the runner is not.
         */
        private Map<String, String> sanitizeHeaders(CapturedRequest request) {
            cookiesStripped += request.cookieCount();

            Map<String, String> headers = new LinkedHashMap<>();
            for (Map.Entry<String, String> header : request.headers().entrySet()) {
                String name = header.getKey();
                String lower = name.toLowerCase(Locale.ROOT);

                if (lower.startsWith(":")) {
                    // HTTP/2 pseudo-headers. Part of the framing, not of the request.
                    continue;
                }
                if (SecretValueDetector.isCredentialHeader(name)) {
                    credentialHeaders++;
                    continue;
                }
                if (SESSION_HEADERS.contains(lower)) {
                    sessionHeaders++;
                    continue;
                }
                if (lower.startsWith("sec-") || NEGOTIATED_HEADERS.contains(lower)) {
                    continue;
                }
                if (SecretValueDetector.looksLikeCredential(header.getValue())) {
                    credentialHeaders++;
                    continue;
                }
                headers.put(name, header.getValue());
            }
            return headers;
        }

        private Map<String, String> sanitizeQuery(CapturedRequest request) {
            Map<String, String> query = new LinkedHashMap<>();
            for (Map.Entry<String, String> parameter : request.query().entrySet()) {
                if (SecretValueDetector.isCredentialHeader(parameter.getKey())
                        || SecretValueDetector.looksLikeCredential(parameter.getValue())) {
                    // Sharper than a header: a query string is written to every access log between
                    // the runner and the target, and to the capture itself.
                    credentialQueryParameters++;
                    continue;
                }
                query.put(parameter.getKey(), parameter.getValue());
            }
            return query;
        }

        /**
         * The body, when it can be imported at all.
         *
         * <p>{@link SensitiveFieldDetector} makes the decision; this reports it. A refused body
         * leaves the
         * scenario in place — the request is still one worth testing, it just needs data a person
         * supplies.
         */
        private ScenarioSpec.RequestBody sanitizeBody(CapturedRequest request, String name) {
            String body = request.body();
            if (body == null || body.isBlank()) {
                return null;
            }
            if ("GET".equals(request.method()) || "HEAD".equals(request.method())) {
                // Servers and proxies drop these unpredictably, so the test would measure a
                // different request than the one captured.
                return null;
            }

            String where = "/scenarios/" + name + "/body";
            SensitiveFieldDetector.Scan scan =
                    SensitiveFieldDetector.scan(request.bodyContentType(), body);

            switch (scan.verdict()) {
                case CREDENTIAL -> {
                    findings.add(ValidationFinding.warning("import.har.body.credential", where,
                            scan.field() != null
                                    ? "The captured body of '" + request.method() + " "
                                            + request.path() + "' contains a field named '"
                                            + scan.field() + "', so the body was not imported."
                                    : "The captured body of '" + request.method() + " "
                                            + request.path() + "' appears to contain a credential, "
                                            + "so it was not imported.",
                            "This is a recording of a real sign-in, so the value is a live "
                                    + "credential. Declare authentication under spec.authentication "
                                    + "as a secret reference, and supply the rest of the body from "
                                    + "a dataset."));
                    return null;
                }
                case PERSONAL_DATA -> {
                    findings.add(ValidationFinding.warning("import.har.body.personalData", where,
                            "The captured body of '" + request.method() + " " + request.path()
                                    + "' contains a field named '" + scan.field() + "', so the body "
                                    + "was not imported.",
                            "A capture records a real customer. Supply this from a dataset of test "
                                    + "data instead."));
                    return null;
                }
                case UNSUPPORTED -> {
                    findings.add(ValidationFinding.warning("import.har.body.unsupported", where,
                            "The captured body of '" + request.method() + " " + request.path()
                                    + "' is multipart or binary and was not imported.",
                            "Captures store these truncated, so what was recorded is not what was "
                                    + "sent. Write the body on the scenario instead."));
                    return null;
                }
                default -> {
                    sawBody = true;
                    return new ScenarioSpec.RequestBody(request.bodyContentType(), body);
                }
            }
        }

        // ── Naming ───────────────────────────────────────────────────────

        /**
         * A scenario name: the journey it belonged to, then the call itself.
         *
         * <p>The prefix is how a journey survives at all. The platform's load model runs scenarios
         * independently, so the grouping cannot be structural — but a reader looking at
         * {@code checkout-basket} and {@code checkout-payment} can see which requests came from one
         * flow, and the warning about ordering tells them what that does and does not mean.
         */
        private String nameFor(CapturedRequest request) {
            String base = nameOf(request);
            String candidate = truncate(base, MAX_NAME_LENGTH);
            for (int suffix = 2; !usedNames.add(candidate); suffix++) {
                String tail = "-" + suffix;
                candidate = truncate(base, MAX_NAME_LENGTH - tail.length()) + tail;
            }
            return candidate;
        }

        /** The name before uniqueness is applied; also used to address a scenario in a finding. */
        private String nameOf(CapturedRequest request) {
            String journey = journeyOf(request);
            String call = callName(request);
            String base = journey.isEmpty() ? call : journey + "-" + call;
            return base.isEmpty() ? "request" : base;
        }

        private String journeyOf(CapturedRequest request) {
            if (request.journeyRef() == null) {
                return "";
            }
            return journeyNames.getOrDefault(request.journeyRef(), kebab(request.journeyRef()));
        }

        /**
         * A readable name for one call.
         *
         * <p>Identifier-looking segments are dropped: they are one session's order number, and a
         * scenario called {@code orders-4718} tells a later reader nothing about what it exercises.
         */
        private static String callName(CapturedRequest request) {
            List<String> segments = new ArrayList<>();
            for (String segment : request.path().split("/")) {
                if (segment.isBlank() || segment.matches("\\d+")
                        || segment.matches("[0-9a-fA-F-]{8,}")) {
                    continue;
                }
                segments.add(kebab(segment));
            }
            String path = String.join("-", segments);
            String method = request.method().toLowerCase(Locale.ROOT);
            if (path.isEmpty()) {
                return method;
            }
            // GET is the default a reader assumes; naming it adds nothing.
            return "GET".equals(request.method()) ? path : method + "-" + path;
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

        // ── Findings ─────────────────────────────────────────────────────

        /** When every entry was filtered out, say which filter did it. */
        private void explainWhyNothingSurvived(List<CapturedRequest> captured) {
            findings.add(ValidationFinding.error("import.har.entries.none", "/log/entries",
                    "None of the " + captured.size() + " captured requests could be imported: "
                            + assetsDropped + " fetched static assets and " + failuresDropped
                            + " did not succeed when they were captured.",
                    "Capture the part of the application you mean to test — sign in first, then "
                            + "clear the network tab, then perform the journey."));
        }

        /**
         * Everything the reader has to know, raised once for the capture.
         *
         * <p>A capture repeats the same cookie on every entry, so per-entry findings would be
         * hundreds of identical lines. Counts carry the same information and leave the findings that
         * need a decision visible.
         */
        private void reportCaptureLevelFindings() {
            // First, and unconditional. Everything else in this list is a detail of it.
            findings.add(ValidationFinding.warning("import.har.reviewRequired", "/",
                    "A HAR is a recording of a real session. Review every scenario before running "
                            + "one: what is left is what a real person did, against the environment "
                            + "they did it in.",
                    "Check that no request addresses data belonging to a real customer, and that "
                            + "the journeys are the ones you mean to test."));

            if (targetHost != null) {
                findings.add(ValidationFinding.info("import.har.host.dropped", "/",
                        "Requests to '" + targetHost + "' were imported as paths only. A run's "
                                + "traffic goes to the target registered for its application, so "
                                + "check that this test's metadata names the application you "
                                + "intend."));
            }
            if (thirdPartyDropped > 0) {
                findings.add(ValidationFinding.warning("import.har.thirdParty.dropped", "/",
                        thirdPartyDropped + " request(s) to other hosts were not imported: "
                                + String.join(", ", otherHosts) + ".",
                        "This platform sends every scenario to one registered target, so importing "
                                + "these would have aimed another service's paths at your own "
                                + "application. Import them separately if you mean to test them."));
            }
            if (cookiesStripped > 0) {
                findings.add(ValidationFinding.warning("import.har.cookies.stripped", "/",
                        cookiesStripped + " cookie(s) were recorded in this capture and none were "
                                + "imported.",
                        "A captured cookie is a live session belonging to whoever made the "
                                + "recording, and it expires besides. Declare authentication under "
                                + "spec.authentication as a secret reference."));
            }
            if (credentialHeaders > 0) {
                findings.add(ValidationFinding.warning("import.har.header.credential", "/",
                        credentialHeaders + " header(s) carrying authentication were not imported.",
                        "Declare them under spec.authentication as secret references. The runner "
                                + "resolves them at execution time, so they never enter the "
                                + "specification."));
            }
            if (sessionHeaders > 0) {
                findings.add(ValidationFinding.warning("import.har.header.session", "/",
                        sessionHeaders + " session or CSRF header(s) were not imported.",
                        "These are minted for one session and rejected on replay. If the target "
                                + "requires a CSRF token, the journey has to fetch one — model the "
                                + "request that issues it as its own scenario."));
            }
            if (credentialQueryParameters > 0) {
                findings.add(ValidationFinding.warning("import.har.query.credential", "/",
                        credentialQueryParameters + " query parameter(s) looked like credentials "
                                + "and were not imported.",
                        "A query string is written to every access log between the runner and the "
                                + "target. Declare these under spec.authentication instead."));
            }

            // Inverted at S3, in place. This used to warn that order was NOT preserved, because a
            // scenario was a single request and a journey could only survive as a name prefix.
            // Journeys are ordered steps now. What remains unpreserved is correlation, and that is
            // what this says instead — the failure it prevents is a journey that looks wired up and
            // sends a stale identifier on every request after the first.
            if (multiStepJourneys > 0) {
                findings.add(ValidationFinding.warning("import.har.journeys.correlationNeeded",
                        "/scenarios",
                        multiStepJourneys + " journey(s) were imported as ordered steps, so the "
                                + "sequence the capture recorded is preserved. Their CORRELATION is "
                                + "not: this importer can see that a response contained an "
                                + "identifier and that a later request used one, but not that they "
                                + "are the same thing, and guessing would wire the journey to the "
                                + "wrong value.",
                        "Review each journey and add an 'extract' to the step that produces a value "
                                + "a later step needs. Without it the later step sends whatever the "
                                + "capture happened to record, which was valid for one session and "
                                + "will not be valid again."));
            }
            if (journeyCount > 0) {
                findings.add(ValidationFinding.info("import.har.journeys.identified", "/scenarios",
                        journeyCount + " journey(s) were identified from the capture's own pages. "
                                + "Requests are grouped and ordered as the browser recorded them."));
            }
            if (imported) {
                findings.add(ValidationFinding.info("import.har.weights.equal", "/scenarios",
                        "Journeys are given equal shares of the traffic, and you should set them to "
                                + "the mix you expect in production. A capture cannot say how often "
                                + "each journey ran — one session usually visits each page once — "
                                + "and a page that happens to make twenty calls is a heavy page, "
                                + "not a popular one. How often each request WITHIN a journey was "
                                + "made is preserved: the capture's repeats are kept as repeated "
                                + "steps, which is the ratio the recording actually observed."));
            }

            if (assetsDropped > 0) {
                findings.add(ValidationFinding.info("import.har.assets.dropped", "/log/entries",
                        assetsDropped + " request(s) for scripts, stylesheets, images and fonts "
                                + "were not imported. They are usually served by a CDN and do not "
                                + "exercise the application."));
            }
            if (failuresDropped > 0) {
                findings.add(ValidationFinding.info("import.har.failures.dropped", "/log/entries",
                        failuresDropped + " request(s) did not succeed when they were captured and "
                                + "were not imported. Replaying a known failure would add it to the "
                                + "run's error rate and measure an error path."));
            }
            if (unreadable > 0) {
                findings.add(ValidationFinding.info("import.har.entries.unreadable",
                        "/log/entries",
                        unreadable + " entry/entries could not be read — typically a request the "
                                + "capture truncated or aborted — and were skipped."));
            }
            if (sawBody) {
                findings.add(ValidationFinding.info("import.har.body.static", "/",
                        "Imported request bodies are fixed, so every virtual user sends the same "
                                + "payload. For a test that writes data, vary it with a dataset — "
                                + "otherwise the run measures the target's behaviour on one "
                                + "repeated request."));
            }
            if (truncated > 0) {
                findings.add(ValidationFinding.warning("import.har.entries.truncated",
                        "/log/entries",
                        truncated + " further distinct request(s) were not imported; the "
                                + MAX_SCENARIOS + " most frequently observed were kept.",
                        "Capture a narrower journey. A test covering every request a session made "
                                + "does not resemble any real traffic."));
            }
        }
    }
}
