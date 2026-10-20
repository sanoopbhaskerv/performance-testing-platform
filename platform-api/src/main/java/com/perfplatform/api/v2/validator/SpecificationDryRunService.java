package com.perfplatform.api.v2.validator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.perfplatform.api.v2.dto.ValidationResponse;
import com.perfplatform.api.v2.dto.ValidatorResponse;
import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.port.TargetResolver;
import com.perfplatform.domain.port.TeamDirectory;
import com.perfplatform.domain.security.CallerContext;
import com.perfplatform.domain.security.Scope;
import com.perfplatform.domain.spec.PerformanceTestSpecification;
import com.perfplatform.domain.spec.PerformanceTestSpecification.AssertionOperator;
import com.perfplatform.domain.spec.PerformanceTestSpecification.AssertionSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.AssertionSubject;
import com.perfplatform.domain.spec.PerformanceTestSpecification.ExtractionSource;
import com.perfplatform.domain.spec.PerformanceTestSpecification.ExtractionSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.ScenarioSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.StepSpec;
import com.perfplatform.domain.spec.WorkloadType;
import com.perfplatform.domain.target.TargetAddress;
import com.perfplatform.domain.target.TargetPolicy;
import com.perfplatform.domain.validation.SecretValueDetector;
import com.perfplatform.domain.validation.Severity;
import com.perfplatform.domain.validation.ValidationFinding;
import com.perfplatform.domain.validation.ValidationResult;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Executes the Validator's single iteration.
 *
 * <p>This is deliberately not a test run: it persists nothing, claims no capacity slot and never
 * asks an {@code ExecutionProvider} to provision a runner. It makes real requests, though, so it
 * uses the same caller/team authorization and server-side target resolver as a run.
 */
public class SpecificationDryRunService {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration MIN_INTERVAL_PER_TEAM = Duration.ofSeconds(1);
    private static final int BODY_PREVIEW_LIMIT = 1_200;
    private static final Pattern COOKIE_PAIR = Pattern.compile("^\\s*([^=;]+)=([^;]*)");

    private final TeamDirectory teams;
    private final TargetResolver targets;
    private final HttpClient http;
    private final ObjectMapper json;
    private final Clock clock;
    private final Map<TeamId, Instant> lastValidationByTeam = new ConcurrentHashMap<>();

    public SpecificationDryRunService(TeamDirectory teams, TargetResolver targets, HttpClient http,
            ObjectMapper json, Clock clock) {
        this.teams = teams;
        this.targets = targets;
        this.http = http;
        if (http.followRedirects() != HttpClient.Redirect.NEVER) {
            throw new IllegalArgumentException("Validator HTTP clients must disable automatic redirects");
        }
        this.json = json;
        this.clock = clock;
    }

    /** Validates a specification on its own, with no recording to compare correlations against. */
    public ValidatorResponse validate(PerformanceTestSpecification specification,
            ValidationResult existingFindings, CallerContext caller) {
        return validate(specification, existingFindings, caller, CorrelationBaseline.none());
    }

    /**
     * Validates a specification, judging its correlations against what a recording captured.
     *
     * @param baseline the literals the recording sent for each correlated variable. With one, the
     *                 Validator can say whether a rule carried a fresh value or replayed the
     *                 capture; without one it reports only that the value was extracted and used
     */
    public ValidatorResponse validate(PerformanceTestSpecification specification,
            ValidationResult existingFindings, CallerContext caller, CorrelationBaseline baseline) {

        List<ValidationFinding> findings = new ArrayList<>(existingFindings.findings());
        if (!existingFindings.isValid()) {
            return response(false, null, findings, List.of());
        }

        String teamName = specification.metadata().team();
        TeamId teamId = teams.findByName(teamName).orElse(null);
        if (teamId == null) {
            findings.add(ValidationFinding.error("validator.team.unknown", "/metadata/team",
                    "This team is not registered, so the Validator cannot resolve a target.",
                    "Choose a registered team before validating against a live target."));
            return response(false, null, findings, List.of());
        }

        caller.requireAct(Scope.RUNS_EXECUTE, teamId);

        Optional<ValidationFinding> rateLimit = rateLimit(teamId);
        if (rateLimit.isPresent()) {
            findings.add(rateLimit.get());
            return response(false, null, findings, List.of());
        }

        String application = specification.metadata().application();
        String environment = specification.spec().environment().name();
        Optional<TargetAddress> target = targets.resolve(teamId, application, environment);
        if (target.isEmpty()) {
            findings.add(ValidationFinding.error("validator.target.unresolved", "/metadata/application",
                    "The Validator could not resolve a permitted target for this application.",
                    "Check the application name and target registration before validating."));
            return response(false, null, findings, List.of());
        }

        List<ValidatorResponse.JourneyResult> journeys = new ArrayList<>();
        for (PerformanceTestSpecification.WorkloadSpec workload
                : specification.workloadsOfType(WorkloadType.PROTOCOL)) {
            for (ScenarioSpec scenario : workload.scenarios()) {
                journeys.add(validateJourney(target.get(), scenario, baseline, findings));
            }
        }

        if (journeys.isEmpty()) {
            findings.add(ValidationFinding.warning("validator.workload.unsupported", "/spec/workloads",
                    "The Validator can execute protocol journeys only in this release.",
                    "Add an API workload, or validate browser workloads after the browser engine is available."));
        }

        boolean valid = findings.stream().noneMatch(finding -> finding.severity() == Severity.ERROR)
                && journeys.stream().allMatch(ValidatorResponse.JourneyResult::passed);
        return response(valid, target.get().baseUrl(), findings, journeys);
    }

    private Optional<ValidationFinding> rateLimit(TeamId teamId) {
        Instant now = clock.instant();
        Instant previous = lastValidationByTeam.put(teamId, now);
        if (previous != null && previous.plus(MIN_INTERVAL_PER_TEAM).isAfter(now)) {
            return Optional.of(ValidationFinding.error("validator.rateLimited", "/",
                    "A Validator run was requested too soon after the previous one for this team.",
                    "Wait a moment and validate again."));
        }
        return Optional.empty();
    }

    private ValidatorResponse.JourneyResult validateJourney(TargetAddress target,
            ScenarioSpec scenario, CorrelationBaseline baseline, List<ValidationFinding> findings) {

        Map<String, String> variables = new LinkedHashMap<>();
        List<ValidatorResponse.StepResult> steps = new ArrayList<>();
        boolean passed = true;

        for (StepSpec step : scenario.asSteps()) {
            ValidatorResponse.StepResult result = validateStep(target, scenario.name(), step, variables);
            steps.add(result);
            if (!result.passed()) {
                passed = false;
                findings.add(ValidationFinding.error("validator.step.failed",
                        "/spec/workloads/scenarios/" + scenario.name() + "/steps/" + step.name(),
                        "Step '" + step.name() + "' failed during validation.",
                        result.error() == null ? "Inspect the request and response evidence."
                                : result.error()));
                break;
            }
        }

        // Judged after the whole journey, not per step: whether a correlation did anything is a
        // question about the steps that come *after* the one that extracted it.
        List<ValidatorResponse.CorrelationProof> correlations =
                new CorrelationProofEvaluator(this::preview)
                        .evaluate(scenario, variables, baseline, findings);

        return new ValidatorResponse.JourneyResult(scenario.name(), passed, steps, correlations);
    }

    private ValidatorResponse.StepResult validateStep(TargetAddress target, String scenarioName,
            StepSpec step, Map<String, String> variables) {

        String method = step.methodOrDefault();
        String path = replace(step.path(), variables);
        Map<String, String> query = replaceMap(step.query(), variables);
        Map<String, String> headers = replaceMap(step.headers(), variables);
        String body = step.body() == null ? null : replace(step.body().content(), variables);
        URI uri = uri(target, path, query);

        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT);
        headers.forEach(builder::header);
        if (step.body() != null) {
            builder.header("Content-Type", step.body().contentTypeOrDefault());
        }
        builder.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));

        ValidatorResponse.RequestEvidence request = new ValidatorResponse.RequestEvidence(
                uri.toString(), redactHeaders(headers), preview(body));

        long started = System.nanoTime();
        try {
            HttpResponse<String> response = sendWithinTarget(builder.build(), target);
            long durationMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

            Map<String, String> responseHeaders = firstHeaders(response);
            ValidatorResponse.ResponseEvidence evidence = new ValidatorResponse.ResponseEvidence(
                    response.statusCode(), redactHeaders(responseHeaders), preview(response.body()));

            List<ValidatorResponse.ExtractionResult> extractions =
                    extractAll(step.extract(), response, variables);
            boolean extracted = extractions.stream().allMatch(ValidatorResponse.ExtractionResult::resolved);
            List<ValidatorResponse.AssertionResult> assertions =
                    assertAll(step.expect(), response);
            boolean asserted = assertions.stream().allMatch(ValidatorResponse.AssertionResult::passed);
            boolean passed = extracted && asserted;
            String error = passed ? null : failureMessage(extractions, assertions);

            return new ValidatorResponse.StepResult(step.name(), method, path, passed,
                    durationMillis, request, evidence, extractions, assertions, error);
        } catch (IOException e) {
            return failedTransportStep(step, method, path, request,
                    "The request could not reach the target: " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failedTransportStep(step, method, path, request,
                    "The validation request was interrupted.");
        } catch (RuntimeException e) {
            return failedTransportStep(step, method, path, request,
                    "The request could not be prepared: " + e.getMessage());
        }
    }

    /**
     * Follow ordinary application redirects only within the registered origin. Automatic HTTP
     * redirects bypass target checks, so no connection is made to a new origin, even when the
     * redirect was returned by a permitted target. Revalidate DNS policy before every hop.
     */
    private HttpResponse<String> sendWithinTarget(HttpRequest request, TargetAddress target)
            throws IOException, InterruptedException {
        for (int redirects = 0; ; redirects++) {
            TargetAddress destination = TargetAddress.parse(request.uri().toString());
            if (!target.scheme().equals(destination.scheme()) || !target.host().equalsIgnoreCase(destination.host())
                    || target.port() != destination.port()) {
                throw new IllegalArgumentException("The target redirected outside its registered origin. "
                        + "Register the final origin explicitly before validating it.");
            }
            TargetPolicy.require(destination);
            HttpResponse<String> response = http.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            Optional<String> location = response.headers().firstValue("Location");
            if (!(status == 301 || status == 302 || status == 303 || status == 307 || status == 308)
                    || location.isEmpty()) return response;
            if (redirects >= 5) throw new IllegalArgumentException("The target exceeded five redirects.");
            URI next = request.uri().resolve(location.orElseThrow());
            // Reject active/user-info URL forms before the next iteration can send headers/body.
            if (next.getUserInfo() != null || next.getFragment() != null) {
                throw new IllegalArgumentException("The redirect URL contains unsupported user information or a fragment.");
            }
            boolean get = status == 303 && !request.method().equals("HEAD")
                    || (status == 301 || status == 302) && request.method().equals("POST");
            HttpRequest.Builder redirected = HttpRequest.newBuilder(next).timeout(REQUEST_TIMEOUT);
            request.headers().map().forEach((name, values) -> {
                if (!get || !name.equalsIgnoreCase("Content-Type") && !name.equalsIgnoreCase("Content-Length")) {
                    values.forEach(value -> redirected.header(name, value));
                }
            });
            request = redirected.method(get ? "GET" : request.method(), get
                    ? HttpRequest.BodyPublishers.noBody()
                    : request.bodyPublisher().orElse(HttpRequest.BodyPublishers.noBody())).build();
        }
    }

    private List<ValidatorResponse.ExtractionResult> extractAll(List<ExtractionSpec> specs,
            HttpResponse<String> response, Map<String, String> variables) {
        List<ValidatorResponse.ExtractionResult> results = new ArrayList<>();
        for (ExtractionSpec extraction : specs) {
            Optional<String> value = extract(extraction, response);
            boolean resolved = value.isPresent() || !extraction.required();
            value.ifPresent(actual -> variables.put(extraction.name(), actual));
            results.add(new ValidatorResponse.ExtractionResult(
                    extraction.name(),
                    external(extraction.from().name()),
                    extraction.expression(),
                    resolved,
                    value.map(this::preview).orElse(null),
                    resolved ? null : "No value matched this extraction."));
        }
        return results;
    }

    private Optional<String> extract(ExtractionSpec extraction, HttpResponse<String> response) {
        return switch (extraction.from()) {
            case STATUS -> Optional.of(String.valueOf(response.statusCode()));
            case HEADER -> firstHeader(response, extraction.expression());
            case SET_COOKIE -> cookie(response, extraction.expression());
            case REGEX_BODY -> regex(response.body(), extraction.expression());
            case JSON_BODY -> jsonPath(response.body(), extraction.expression());
        };
    }

    private List<ValidatorResponse.AssertionResult> assertAll(List<AssertionSpec> specs,
            HttpResponse<String> response) {
        List<ValidatorResponse.AssertionResult> results = new ArrayList<>();
        for (AssertionSpec assertion : specs) {
            String observed = observed(assertion.subject(), response);
            boolean passed = compare(assertion.operator(), observed, assertion.value());
            results.add(new ValidatorResponse.AssertionResult(
                    assertion.subject().name().toLowerCase(Locale.ROOT),
                    assertion.operator().name().toLowerCase(Locale.ROOT),
                    assertion.value(),
                    passed,
                    preview(observed),
                    passed ? null : "Expected " + assertion.subject().name().toLowerCase(Locale.ROOT)
                            + " to " + assertion.operator().name().toLowerCase(Locale.ROOT)
                            + " " + assertion.value() + "."));
        }
        return results;
    }

    private static String observed(AssertionSubject subject, HttpResponse<String> response) {
        return subject == AssertionSubject.STATUS
                ? String.valueOf(response.statusCode())
                : response.body();
    }

    private static boolean compare(AssertionOperator operator, String observed, String expected) {
        if (observed == null) {
            return false;
        }
        return switch (operator) {
            case EQUALS -> observed.equals(expected);
            case CONTAINS -> observed.contains(expected);
            case MATCHES -> Pattern.compile(expected).matcher(observed).find();
        };
    }

    private Optional<String> jsonPath(String body, String expression) {
        if (body == null || expression == null || !expression.startsWith("$")) {
            return Optional.empty();
        }
        try {
            JsonNode node = json.readTree(body);
            if ("$".equals(expression)) {
                return scalar(node);
            }
            String remaining = expression.startsWith("$.") ? expression.substring(2) : expression.substring(1);
            for (String part : remaining.split("\\.")) {
                if (part.isBlank()) {
                    return Optional.empty();
                }
                int bracket = part.indexOf('[');
                String field = bracket == -1 ? part : part.substring(0, bracket);
                node = node.path(field);
                if (node.isMissingNode()) {
                    return Optional.empty();
                }
                if (bracket != -1) {
                    int end = part.indexOf(']', bracket);
                    if (end == -1) {
                        return Optional.empty();
                    }
                    int index = Integer.parseInt(part.substring(bracket + 1, end));
                    node = node.path(index);
                }
            }
            return scalar(node);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static Optional<String> scalar(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Optional.empty();
        }
        return node.isTextual() || node.isNumber() || node.isBoolean()
                ? Optional.of(node.asText())
                : Optional.empty();
    }

    private static Optional<String> regex(String body, String expression) {
        if (body == null || expression == null) {
            return Optional.empty();
        }
        Matcher matcher = Pattern.compile(expression).matcher(body);
        return matcher.find() && matcher.groupCount() >= 1
                ? Optional.ofNullable(matcher.group(1))
                : Optional.empty();
    }

    private static Optional<String> firstHeader(HttpResponse<String> response, String name) {
        return response.headers().firstValue(name);
    }

    private static Optional<String> cookie(HttpResponse<String> response, String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        for (String header : response.headers().allValues("set-cookie")) {
            Matcher matcher = COOKIE_PAIR.matcher(header);
            if (matcher.find() && name.equals(matcher.group(1))) {
                return Optional.of(matcher.group(2));
            }
        }
        return Optional.empty();
    }

    private static Map<String, String> firstHeaders(HttpResponse<String> response) {
        Map<String, String> headers = new LinkedHashMap<>();
        response.headers().map().forEach((name, values) -> {
            if (!values.isEmpty()) {
                headers.put(name, values.get(0));
            }
        });
        return headers;
    }

    private static URI uri(TargetAddress target, String path, Map<String, String> query) {
        String normalizedPath = path == null || path.isBlank() ? "/" : path;
        if (!normalizedPath.startsWith("/")) {
            normalizedPath = "/" + normalizedPath;
        }
        StringBuilder url = new StringBuilder(target.baseUrl()).append(normalizedPath);
        if (!query.isEmpty()) {
            url.append('?');
            boolean first = true;
            for (Map.Entry<String, String> entry : query.entrySet()) {
                if (!first) {
                    url.append('&');
                }
                first = false;
                url.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
            }
        }
        return URI.create(url.toString());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static Map<String, String> replaceMap(Map<String, String> values,
            Map<String, String> variables) {
        Map<String, String> replaced = new LinkedHashMap<>();
        values.forEach((key, value) -> replaced.put(key, replace(value, variables)));
        return replaced;
    }

    private static String replace(String text, Map<String, String> variables) {
        if (text == null) {
            return null;
        }
        String next = text;
        for (Map.Entry<String, String> variable : variables.entrySet()) {
            next = next.replace("${" + variable.getKey() + "}", variable.getValue());
        }
        return next;
    }

    private ValidatorResponse.StepResult failedTransportStep(StepSpec step, String method,
            String path, ValidatorResponse.RequestEvidence request, String error) {
        return new ValidatorResponse.StepResult(step.name(), method, path, false, 0,
                request, new ValidatorResponse.ResponseEvidence(0, Map.of(), ""),
                List.of(), List.of(), error);
    }

    private static String failureMessage(List<ValidatorResponse.ExtractionResult> extractions,
            List<ValidatorResponse.AssertionResult> assertions) {
        return extractions.stream()
                .filter(extraction -> !extraction.resolved())
                .findFirst()
                .map(ValidatorResponse.ExtractionResult::message)
                .orElseGet(() -> assertions.stream()
                        .filter(assertion -> !assertion.passed())
                        .findFirst()
                        .map(ValidatorResponse.AssertionResult::message)
                        .orElse("The step failed validation."));
    }

    private Map<String, String> redactHeaders(Map<String, String> headers) {
        Map<String, String> redacted = new LinkedHashMap<>();
        headers.forEach((name, value) -> redacted.put(name,
                SecretValueDetector.isCredentialHeader(name) ? "[redacted]" : preview(value)));
        return redacted;
    }

    private String preview(String value) {
        if (value == null || value.isBlank()) {
            return value == null ? null : "";
        }
        if (SecretValueDetector.looksLikeCredential(value)) {
            return "[redacted]";
        }
        return value.length() <= BODY_PREVIEW_LIMIT
                ? value
                : value.substring(0, BODY_PREVIEW_LIMIT) + "...";
    }

    private static String external(String enumName) {
        return enumName.toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static ValidatorResponse response(boolean valid, String target,
            List<ValidationFinding> findings, List<ValidatorResponse.JourneyResult> journeys) {
        return new ValidatorResponse(valid, target,
                ValidationResult.of(findings).findings().stream()
                        .map(ValidationResponse.Finding::from)
                        .toList(),
                List.copyOf(journeys));
    }
}
