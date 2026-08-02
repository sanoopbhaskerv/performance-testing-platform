package com.perfplatform.domain.validation;

import com.perfplatform.domain.dataset.DatasetBinding;
import com.perfplatform.domain.dataset.DatasetUri;
import com.perfplatform.domain.spec.PerformanceTestSpecification;
import com.perfplatform.domain.spec.PerformanceTestSpecification.ObjectiveSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.ScenarioSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.SourceSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.WorkloadSpec;
import com.perfplatform.domain.spec.SourceType;
import com.perfplatform.domain.spec.WorkloadType;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates relationships a schema cannot express.
 *
 * <p>Schema validation answers "is this document well formed"; this answers "does this test make
 * sense". A specification can satisfy every type constraint and still reference a source that
 * does not exist, weight its scenarios to 143%, or declare objectives scoped to a workload it
 * never defines. Those only surface by looking across fields.
 *
 * <p>Deliberately excluded: anything requiring the network. DNS, TLS and target health are
 * connectivity preflight and run separately, against the runner cell that will execute the test.
 * Mixing them in would make validation slow, flaky and impossible to run in a pull request.
 *
 * <p>Also excluded: quota and target policy. Those are enforced server-side by admission control
 * against configured limits, not against values the specification supplies about itself.
 * Warnings here are advisory only.
 *
 * <p>This class is pure. Same input, same findings, no I/O.
 */
public final class SemanticValidator {

    /** Kubernetes-style name: safe in object keys, Job labels and URLs. */
    private static final Pattern NAME = Pattern.compile("^[a-z0-9]([-a-z0-9]*[a-z0-9])?$");

    /** An immutable git ref: a full or abbreviated commit SHA, never a branch. */
    private static final Pattern COMMIT_SHA = Pattern.compile("^[0-9a-f]{7,40}$");

    private static final int MAX_NAME_LENGTH = 63;

    private SemanticValidator() {
    }

    public static ValidationResult validate(PerformanceTestSpecification specification) {
        List<ValidationFinding> findings = new ArrayList<>();

        if (specification == null) {
            findings.add(ValidationFinding.error("spec.missing", "",
                    "No specification was provided.", null));
            return ValidationResult.of(findings);
        }

        validateDocument(specification, findings);
        validateMetadata(specification, findings);

        if (specification.spec() == null) {
            findings.add(ValidationFinding.error("spec.body.missing", "/spec",
                    "The specification body is missing.",
                    "Add a spec section declaring at least an environment and one workload."));
            return ValidationResult.of(findings);
        }

        validateEnvironment(specification, findings);
        validateSources(specification, findings);
        validateAuthentication(specification, findings);
        validateDatasets(specification, findings);
        validateWorkloads(specification, findings);
        validateObjectives(specification, findings);
        validateGovernance(specification, findings);

        return ValidationResult.of(findings);
    }

    // ── Document ─────────────────────────────────────────────────────────

    private static void validateDocument(PerformanceTestSpecification spec,
            List<ValidationFinding> findings) {
        if (isBlank(spec.apiVersion())) {
            findings.add(ValidationFinding.error("spec.apiVersion.missing", "/apiVersion",
                    "apiVersion is required.",
                    "Set apiVersion: " + PerformanceTestSpecification.SUPPORTED_API_VERSION));
        } else if (!PerformanceTestSpecification.SUPPORTED_API_VERSION.equals(spec.apiVersion())) {
            findings.add(ValidationFinding.error("spec.apiVersion.unsupported", "/apiVersion",
                    "Unsupported apiVersion '" + spec.apiVersion() + "'.",
                    "This release understands "
                            + PerformanceTestSpecification.SUPPORTED_API_VERSION + "."));
        }

        if (isBlank(spec.kind())) {
            findings.add(ValidationFinding.error("spec.kind.missing", "/kind",
                    "kind is required.",
                    "Set kind: " + PerformanceTestSpecification.SUPPORTED_KIND));
        } else if (!PerformanceTestSpecification.SUPPORTED_KIND.equals(spec.kind())) {
            findings.add(ValidationFinding.error("spec.kind.unsupported", "/kind",
                    "Unsupported kind '" + spec.kind() + "'.",
                    "This schema defines " + PerformanceTestSpecification.SUPPORTED_KIND + "."));
        }
    }

    private static void validateMetadata(PerformanceTestSpecification spec,
            List<ValidationFinding> findings) {
        var metadata = spec.metadata();
        if (metadata == null) {
            findings.add(ValidationFinding.error("spec.metadata.missing", "/metadata",
                    "metadata is required.",
                    "Declare at least name, team and application."));
            return;
        }
        requireName(metadata.name(), "/metadata/name", "metadata.name", findings);
        requireName(metadata.team(), "/metadata/team", "metadata.team", findings);
        requireName(metadata.application(), "/metadata/application", "metadata.application", findings);
    }

    // ── Body ─────────────────────────────────────────────────────────────

    private static void validateEnvironment(PerformanceTestSpecification spec,
            List<ValidationFinding> findings) {
        var environment = spec.spec().environment();
        if (environment == null) {
            findings.add(ValidationFinding.error("spec.environment.missing", "/spec/environment",
                    "An environment is required.",
                    "Declare the environment name, runner cell and target class."));
            return;
        }
        requireName(environment.name(), "/spec/environment/name", "environment.name", findings);
        if (isBlank(environment.runnerCell())) {
            findings.add(ValidationFinding.error("spec.environment.runnerCell.missing",
                    "/spec/environment/runnerCell",
                    "A runner cell is required.",
                    "Name the execution cell with network access to the target."));
        }
        if (environment.targetClass() == null) {
            findings.add(ValidationFinding.error("spec.environment.targetClass.missing",
                    "/spec/environment/targetClass",
                    "A target class is required.",
                    "Set targetClass to non-production or production."));
        }
    }

    private static void validateSources(PerformanceTestSpecification spec,
            List<ValidationFinding> findings) {
        List<SourceSpec> sources = spec.spec().sources();
        Set<String> seen = new HashSet<>();

        for (int i = 0; i < sources.size(); i++) {
            SourceSpec source = sources.get(i);
            String path = "/spec/sources/" + i;

            requireName(source.name(), path + "/name", "source name", findings);
            if (source.name() != null && !seen.add(source.name())) {
                findings.add(ValidationFinding.error("spec.source.name.duplicate", path + "/name",
                        "Duplicate source name '" + source.name() + "'.",
                        "Source names must be unique so workloads can reference them unambiguously."));
            }

            if (source.type() == null) {
                findings.add(ValidationFinding.error("spec.source.type.missing", path + "/type",
                        "A source type is required.", null));
                continue;
            }

            if (source.type() == SourceType.PLAYWRIGHT) {
                validatePlaywrightSource(source, path, findings);
            } else if (source.type() != SourceType.MANUAL && isBlank(source.uri())) {
                findings.add(ValidationFinding.error("spec.source.uri.missing", path + "/uri",
                        "A " + source.type().name().toLowerCase() + " source requires a uri.",
                        "Reference the uploaded artifact, for example artifact://<app>/openapi.yaml."));
            }
        }
    }

    private static void validatePlaywrightSource(SourceSpec source, String path,
            List<ValidationFinding> findings) {
        var git = source.git();
        if (git == null) {
            findings.add(ValidationFinding.error("spec.source.git.missing", path + "/git",
                    "A Playwright source requires git coordinates.",
                    "Provide repository, ref and path."));
            return;
        }
        if (isBlank(git.repository())) {
            findings.add(ValidationFinding.error("spec.source.git.repository.missing",
                    path + "/git/repository", "A repository is required.", null));
        }
        if (isBlank(git.path())) {
            findings.add(ValidationFinding.error("spec.source.git.path.missing",
                    path + "/git/path", "A test path is required.", null));
        } else if (git.path().contains("..")) {
            // A path escaping the checked-out workspace would let imported material read the
            // runner's filesystem outside the repository.
            findings.add(ValidationFinding.error("spec.source.git.path.traversal",
                    path + "/git/path",
                    "The test path must stay within the checked-out workspace.",
                    "Remove '..' segments and use a path relative to the repository root."));
        }

        if (isBlank(git.ref())) {
            findings.add(ValidationFinding.error("spec.source.git.ref.missing",
                    path + "/git/ref", "An immutable git ref is required.",
                    "Pin the source to a commit SHA."));
        } else if (!COMMIT_SHA.matcher(git.ref().trim()).matches()) {
            // A moving branch means the code that ran cannot be recovered from the stored
            // version, so the run is not reproducible and the review that approved it is void.
            findings.add(ValidationFinding.error("spec.source.git.ref.mutable",
                    path + "/git/ref",
                    "Ref '" + git.ref() + "' is not an immutable commit SHA.",
                    "Pin to a commit SHA. A branch or tag can move after review."));
        }
    }

    private static void validateAuthentication(PerformanceTestSpecification spec,
            List<ValidationFinding> findings) {
        var authentications = spec.spec().authentication();
        for (int i = 0; i < authentications.size(); i++) {
            var authentication = authentications.get(i);
            String path = "/spec/authentication/" + i;

            requireName(authentication.name(), path + "/name", "authentication name", findings);
            if (isBlank(authentication.type())) {
                findings.add(ValidationFinding.error("spec.authentication.type.missing",
                        path + "/type", "An authentication type is required.", null));
            }
            findings.addAll(SecretValueDetector.check(authentication.secretRef(),
                    path + "/secretRef").findings());
        }
    }

    private static void validateDatasets(PerformanceTestSpecification spec,
            List<ValidationFinding> findings) {
        var datasets = spec.spec().datasets();
        for (int i = 0; i < datasets.size(); i++) {
            var dataset = datasets.get(i);
            String path = "/spec/datasets/" + i;

            requireName(dataset.name(), path + "/name", "dataset name", findings);
            if (dataset.source() == null || isBlank(dataset.source().uri())) {
                findings.add(ValidationFinding.error("spec.dataset.source.missing",
                        path + "/source", "A dataset requires a source uri.", null));
            } else if (isBlank(dataset.source().type())) {
                findings.add(ValidationFinding.error("spec.dataset.source.type.missing",
                        path + "/source/type",
                        "A dataset source requires a type.",
                        "Use object-storage for a stored dataset, or inline for values carried "
                                + "in the specification."));
            } else {
                validateDatasetUri(dataset.source().uri(), path + "/source/uri", findings);
            }
            if (dataset.columns().isEmpty()) {
                findings.add(ValidationFinding.warning("spec.dataset.columns.undeclared",
                        path + "/columns",
                        "This dataset declares no columns, so nothing can check that the values "
                                + "your journeys ask for exist in it.",
                        "List the column names, e.g. columns: [orderId, sku]. They are compared "
                                + "against the file's own header before the run starts."));
            }
            for (int c = 0; c < dataset.columns().size(); c++) {
                if (isBlank(dataset.columns().get(c))) {
                    findings.add(ValidationFinding.error("spec.dataset.column.blank",
                            path + "/columns/" + c, "A column name must not be blank.", null));
                }
            }
            if (!dataset.partitionByShard()) {
                findings.add(ValidationFinding.warning("spec.dataset.partition.disabled",
                        path + "/partitionByShard",
                        "This dataset is not partitioned by shard.",
                        "If the run is sharded, every shard replays the same rows, which can "
                                + "distort results and duplicate writes to the target."));
            }
        }
    }

    /**
     * Checks that a dataset source names a stored version this platform can actually deliver.
     *
     * <p>Two refusals, and they are different mistakes with different fixes.
     *
     * <p><b>An unversioned reference.</b> {@code dataset://orders} means "whatever orders holds
     * today", so two runs of one immutable definition version would read different data while
     * carrying the same specification digest. That is the same defect as a floating runner image
     * tag, which the platform already refuses at startup, so it is refused here too rather than
     * resolved to the newest upload.
     *
     * <p><b>Anything else.</b> A bucket URL, a file path, a name — none of them resolve, and an
     * unresolved dataset is not an empty dataset: every {@code ${orderId}} would be sent as those
     * literal characters and the run would report fast, healthy-looking latency for a target that
     * answered 404 to everything. The run is refused before any load is generated, but this catches
     * it a great deal earlier, at authoring time, where it costs nothing.
     */
    private static void validateDatasetUri(String uri, String path,
            List<ValidationFinding> findings) {

        if (DatasetUri.parse(uri).isPresent()) {
            return;
        }
        if (DatasetUri.isUnversioned(uri)) {
            findings.add(ValidationFinding.error("spec.dataset.source.uri.unversioned", path,
                    "A dataset reference must name a version.",
                    "Use dataset://<name>/<version>, e.g. dataset://orders/3. Without a version, "
                            + "two runs of this same immutable test version would read different "
                            + "data."));
            return;
        }
        findings.add(ValidationFinding.error("spec.dataset.source.uri.unresolvable", path,
                "'" + uri + "' does not name a dataset this platform stores.",
                "Upload the file to POST /api/v2/datasets and use the dataset://<name>/<version> "
                        + "uri it returns. An unresolved reference is sent as the literal text "
                        + "${column}, so every request would fail while the run reported fast, "
                        + "healthy-looking latency."));
    }

    /**
     * Every column name the specification's datasets declare.
     *
     * <p>Pooled across datasets rather than tracked per dataset. A scenario says {@code ${orderId}}
     * without naming which dataset it came from — that is the engine's binding model, where every
     * dataset's columns share one variable namespace — so validating per dataset would enforce a
     * distinction the execution does not make.
     */
    private static Set<String> declaredColumns(PerformanceTestSpecification spec) {
        Set<String> columns = new HashSet<>();
        spec.spec().datasets().forEach(dataset -> columns.addAll(dataset.columns()));
        return columns;
    }

    /**
     * Checks that every {@code ${column}} a scenario asks for is one a dataset supplies.
     *
     * <p>An error, not a warning, and for the reason that keeps recurring: an unresolved reference
     * is not left empty, it is sent <em>literally</em>. {@code /orders/${orderId}} goes out with
     * those characters in the path, every request 404s, and the run reports fast, healthy-looking
     * latency for a target that did no work. A misspelled column name is an easy mistake and an
     * expensive one, so it is caught here rather than discovered in a report.
     *
     * <p>When no dataset declares its columns at all, the references cannot be checked against
     * anything and are reported as unverifiable instead — a warning, because the test may well be
     * correct and the platform simply has not been told enough to say so.
     */
    private static void validateDatasetReferences(ScenarioSpec scenario, String scenarioPath,
            Set<String> datasetColumns, List<ValidationFinding> findings) {

        Set<String> referenced = DatasetBinding.referencesIn(scenario);
        if (referenced.isEmpty()) {
            return;
        }

        if (datasetColumns.isEmpty()) {
            findings.add(ValidationFinding.warning("spec.scenario.dataset.unverifiable",
                    scenarioPath,
                    "This journey uses " + String.join(", ", referenced) + ", but no dataset "
                            + "declares any columns, so nothing can confirm those values will be "
                            + "supplied.",
                    "Declare a dataset with a columns list. An unfilled reference is sent as "
                            + "literal text, so the run would measure the target rejecting it."));
            return;
        }

        List<String> unresolved = referenced.stream()
                .filter(name -> !datasetColumns.contains(name))
                .toList();

        if (!unresolved.isEmpty()) {
            findings.add(ValidationFinding.error("spec.scenario.dataset.unresolved",
                    scenarioPath,
                    "This journey uses " + String.join(", ", unresolved)
                            + ", which no dataset supplies.",
                    "Declared columns are " + datasetColumns.stream().sorted().toList()
                            + ". An unfilled reference is sent as literal text, so every request "
                            + "would fail and the run would report the latency of an error path."));
        }
    }

    private static void validateWorkloads(PerformanceTestSpecification spec,
            List<ValidationFinding> findings) {
        List<WorkloadSpec> workloads = spec.spec().workloads();

        if (workloads.isEmpty()) {
            findings.add(ValidationFinding.error("spec.workload.missing", "/spec/workloads",
                    "At least one workload is required.",
                    "Declare a protocol or browser workload describing the traffic to generate."));
            return;
        }

        Set<String> sourceNames = spec.spec().sources().stream()
                .map(SourceSpec::name)
                .filter(name -> name != null)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> workloadNames = new HashSet<>();

        for (int i = 0; i < workloads.size(); i++) {
            WorkloadSpec workload = workloads.get(i);
            String path = "/spec/workloads/" + i;

            requireName(workload.name(), path + "/name", "workload name", findings);
            if (workload.name() != null && !workloadNames.add(workload.name())) {
                findings.add(ValidationFinding.error("spec.workload.name.duplicate", path + "/name",
                        "Duplicate workload name '" + workload.name() + "'.",
                        "Workload names must be unique; objectives and start conditions "
                                + "reference them."));
            }

            if (workload.type() == null) {
                findings.add(ValidationFinding.error("spec.workload.type.missing", path + "/type",
                        "A workload type is required.", null));
                continue;
            }

            if (!isBlank(workload.sourceRef()) && !sourceNames.contains(workload.sourceRef())) {
                findings.add(ValidationFinding.error("spec.workload.sourceRef.unresolved",
                        path + "/sourceRef",
                        "sourceRef '" + workload.sourceRef() + "' does not match any declared source.",
                        "Declare the source under spec.sources, or correct the reference."));
            }

            if (workload.type() == WorkloadType.PROTOCOL) {
                validateProtocolWorkload(workload, path, declaredColumns(spec), findings);
            } else {
                validateBrowserWorkload(workload, path, findings);
            }
        }

        validateStartConditions(workloads, workloadNames, findings);
    }

    private static void validateProtocolWorkload(WorkloadSpec workload, String path,
            Set<String> datasetColumns, List<ValidationFinding> findings) {
        if (workload.model() == null || workload.model().stages().isEmpty()) {
            findings.add(ValidationFinding.error("spec.workload.model.missing", path + "/model",
                    "A protocol workload requires a staged load model.",
                    "Declare stages with a duration and target user count."));
        } else {
            if (workload.model().type() == null) {
                findings.add(ValidationFinding.error("spec.workload.model.type.missing",
                        path + "/model/type",
                        "A load model requires a type.",
                        "Set type: stages. Load, stress, spike and endurance tests are all "
                                + "expressed as a staged ramp."));
            }
            var stages = workload.model().stages();
            for (int s = 0; s < stages.size(); s++) {
                var stage = stages.get(s);
                String stagePath = path + "/model/stages/" + s;
                if (stage.duration() == null || stage.duration().isZero()
                        || stage.duration().isNegative()) {
                    findings.add(ValidationFinding.error("spec.workload.stage.duration.invalid",
                            stagePath + "/duration",
                            "A stage requires a positive duration.", null));
                }
                if (stage.targetUsers() < 0) {
                    findings.add(ValidationFinding.error("spec.workload.stage.users.negative",
                            stagePath + "/targetUsers",
                            "targetUsers must not be negative.", null));
                }
            }
            if (workload.peakUsers() == 0) {
                findings.add(ValidationFinding.error("spec.workload.stage.users.allZero",
                        path + "/model/stages",
                        "Every stage targets zero users, so this workload generates no load.",
                        "Set a positive targetUsers on at least one stage."));
            }
        }

        validateScenarioWeights(workload, path, datasetColumns, findings);

        if (workload.browserUsers() != null) {
            findings.add(ValidationFinding.warning("spec.workload.browserUsers.ignored",
                    path + "/browserUsers",
                    "browserUsers is ignored for a protocol workload.",
                    "Remove it, or change the workload type to browser."));
        }
    }

    private static void validateScenarioWeights(WorkloadSpec workload, String path,
            Set<String> datasetColumns, List<ValidationFinding> findings) {
        List<ScenarioSpec> scenarios = workload.scenarios();
        if (scenarios.isEmpty()) {
            return;
        }

        Set<String> names = new HashSet<>();
        int total = 0;
        for (int s = 0; s < scenarios.size(); s++) {
            ScenarioSpec scenario = scenarios.get(s);
            String scenarioPath = path + "/scenarios/" + s;

            requireName(scenario.name(), scenarioPath + "/name", "scenario name", findings);
            if (scenario.name() != null && !names.add(scenario.name())) {
                findings.add(ValidationFinding.error("spec.scenario.name.duplicate",
                        scenarioPath + "/name",
                        "Duplicate scenario name '" + scenario.name() + "'.", null));
            }
            if (scenario.weight() <= 0) {
                findings.add(ValidationFinding.error("spec.scenario.weight.invalid",
                        scenarioPath + "/weight",
                        "A scenario weight must be greater than zero.",
                        "A zero weight means the scenario never runs; remove it instead."));
            }
            validateScenarioRequest(scenario, scenarioPath, datasetColumns, findings);
            total += scenario.weight();
        }

        if (total != 100) {
            // Not an error: weights are relative, so the run is still executable. But an author
            // writing 60/30/20 almost always meant 60/30/10.
            findings.add(ValidationFinding.warning("spec.scenario.weights.sum",
                    path + "/scenarios",
                    "Scenario weights sum to " + total + ", not 100.",
                    "Weights are treated as relative shares. Adjust them to sum to 100 to make "
                            + "the intended traffic mix explicit."));
        }
    }

    /** HTTP methods a scenario may declare. Anything else is refused rather than passed through. */
    private static final Set<String> SUPPORTED_METHODS =
            Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");

    /**
     * Checks how a scenario says what to call.
     *
     * <p>A scenario states its request directly ({@code method} and {@code path}) or refers to one in
     * an imported source ({@code operationId}). Saying neither is an error: there is nothing to call.
     *
     * <p>A path must be a path. Accepting a full URL here would let a specification choose its own
     * host, which is precisely what the target policy exists to prevent (CLAUDE.md §20) — the address
     * a run may reach is resolved by the platform from the team's registered targets, never stated by
     * the document. Rejected at validation so it is reported to the author, rather than at render
     * time where it would surface as a failed run.
     */
    private static void validateScenarioRequest(ScenarioSpec scenario, String scenarioPath,
            Set<String> datasetColumns, List<ValidationFinding> findings) {

        boolean hasPath = scenario.path() != null && !scenario.path().isBlank();
        boolean hasOperation = scenario.operationId() != null && !scenario.operationId().isBlank();

        if (!hasPath && !hasOperation) {
            findings.add(ValidationFinding.error("spec.scenario.request.missing",
                    scenarioPath,
                    "A scenario must declare either a path, or an operationId from an imported "
                            + "source.",
                    "Add 'path: /api/example' and optionally 'method: POST', or reference an "
                            + "imported operation with 'operationId'."));
            return;
        }

        if (hasPath) {
            String requestPath = scenario.path().trim();
            if (!requestPath.startsWith("/")) {
                findings.add(ValidationFinding.error("spec.scenario.path.invalid",
                        scenarioPath + "/path",
                        "A scenario path must begin with '/'.",
                        "Paths are relative to the base URL the platform resolves for this "
                                + "application. Write '/api/users', not a full URL."));
            } else if (requestPath.contains("://")) {
                findings.add(ValidationFinding.error("spec.scenario.path.absolute",
                        scenarioPath + "/path",
                        "A scenario path must not be a full URL.",
                        "Where a run's traffic goes is resolved from the team's registered targets "
                                + "for this application. A specification cannot name its own host."));
            }
        }

        String method = scenario.method();
        if (method != null && !method.isBlank()
                && !SUPPORTED_METHODS.contains(method.trim().toUpperCase(java.util.Locale.ROOT))) {
            findings.add(ValidationFinding.error("spec.scenario.method.unsupported",
                    scenarioPath + "/method",
                    "HTTP method '" + method + "' is not supported.",
                    "Supported methods are " + SUPPORTED_METHODS.stream().sorted().toList() + "."));
        }

        validateHeaders(scenario, scenarioPath, findings);
        validateQuery(scenario, scenarioPath, findings);
        validateBody(scenario, scenarioPath, findings);
        validateDatasetReferences(scenario, scenarioPath, datasetColumns, findings);
    }

    /**
     * Headers must not carry credentials.
     *
     * <p>Two checks, and the first is the one that cannot be evaded. A header named
     * {@code Authorization} is refused whatever its value, because there is no legitimate reason to
     * write one into a specification — authentication belongs in {@code spec.authentication} as a
     * reference the runner resolves at execution time. The second catches a token written under some
     * other name, and is deliberately narrow: it runs over ordinary values like
     * {@code application/json}, and a validator that cried wolf would be worked around.
     *
     * <p>Neither finding echoes the offending value. A specification is stored, rendered in the UI,
     * committed to Git and returned by the validate endpoint — a finding quoting the secret would
     * spread it further than the document did.
     */
    private static void validateHeaders(ScenarioSpec scenario, String scenarioPath,
            List<ValidationFinding> findings) {

        scenario.headers().forEach((name, value) -> {
            String path = scenarioPath + "/headers/" + name;

            if (isBlank(name)) {
                findings.add(ValidationFinding.error("spec.scenario.header.name.missing",
                        scenarioPath + "/headers", "A header name must not be blank.", null));
                return;
            }

            if (SecretValueDetector.isCredentialHeader(name)) {
                findings.add(ValidationFinding.error("spec.scenario.header.credential", path,
                        "Header '" + name + "' carries credentials and cannot be set in a "
                                + "specification.",
                        "Declare the credential under spec.authentication as a secret reference. "
                                + "The runner resolves it at execution time, so it never appears in "
                                + "the document, the database, or a report."));
                return;
            }

            if (SecretValueDetector.looksLikeCredential(value)) {
                findings.add(ValidationFinding.error("spec.scenario.header.inline-secret", path,
                        "Header '" + name + "' appears to contain a credential value.",
                        "Specifications hold secret references, never secret values. Move it to "
                                + "spec.authentication."));
            }
        });
    }

    /**
     * Query parameters must not carry credentials either — and for a sharper reason than headers.
     *
     * <p>A query string is recorded by every proxy, load balancer and access log between the runner
     * and the target, and lands in browser history and referrer headers besides. A credential in one
     * is disclosed to systems nobody enumerated (CLAUDE.md §20).
     */
    private static void validateQuery(ScenarioSpec scenario, String scenarioPath,
            List<ValidationFinding> findings) {

        scenario.query().forEach((name, value) -> {
            if (isBlank(name)) {
                findings.add(ValidationFinding.error("spec.scenario.query.name.missing",
                        scenarioPath + "/query", "A query parameter name must not be blank.", null));
                return;
            }
            if (SecretValueDetector.looksLikeCredential(value)
                    || SecretValueDetector.isCredentialHeader(name)) {
                findings.add(ValidationFinding.error("spec.scenario.query.inline-secret",
                        scenarioPath + "/query/" + name,
                        "Query parameter '" + name + "' appears to contain a credential value.",
                        "A query string is written to every access log between the runner and the "
                                + "target. Declare the credential under spec.authentication."));
            }
        });
    }

    /**
     * A body may not carry a credential either, and must accompany a method that can send one.
     *
     * <p>The method check is a warning, not an error: {@code DELETE} with a body is unusual but
     * legal, and some APIs do it. A {@code GET} with a body is where it becomes a mistake worth
     * blocking on — many servers and proxies silently drop it, so the test would measure a request
     * the author did not write.
     */
    private static void validateBody(ScenarioSpec scenario, String scenarioPath,
            List<ValidationFinding> findings) {

        if (!scenario.hasBody()) {
            return;
        }

        if ("GET".equals(scenario.methodOrDefault()) || "HEAD".equals(scenario.methodOrDefault())) {
            findings.add(ValidationFinding.error("spec.scenario.body.notAllowed",
                    scenarioPath + "/body",
                    "A " + scenario.methodOrDefault() + " request cannot carry a body.",
                    "Many servers and proxies drop it silently, so the test would measure a "
                            + "different request than the one written. Use query parameters, or a "
                            + "method that carries a body."));
        }

        if (SecretValueDetector.looksLikeCredential(scenario.body().content())) {
            findings.add(ValidationFinding.error("spec.scenario.body.inline-secret",
                    scenarioPath + "/body/content",
                    "The request body appears to contain a credential value.",
                    "Specifications hold secret references, never secret values."));
        }
    }

    private static void validateBrowserWorkload(WorkloadSpec workload, String path,
            List<ValidationFinding> findings) {
        if (workload.browserUsers() == null) {
            findings.add(ValidationFinding.error("spec.workload.browserUsers.missing",
                    path + "/browserUsers",
                    "A browser workload requires browserUsers.",
                    "Browser sessions are budgeted separately from protocol virtual users; "
                            + "start with a small cohort."));
        } else if (workload.browserUsers() <= 0) {
            findings.add(ValidationFinding.error("spec.workload.browserUsers.invalid",
                    path + "/browserUsers",
                    "browserUsers must be greater than zero.", null));
        }

        if (workload.iterationsPerUser() != null && workload.iterationsPerUser() <= 0) {
            findings.add(ValidationFinding.error("spec.workload.iterations.invalid",
                    path + "/iterationsPerUser",
                    "iterationsPerUser must be greater than zero.", null));
        }

        if (workload.model() != null) {
            findings.add(ValidationFinding.warning("spec.workload.model.ignored", path + "/model",
                    "A staged load model is ignored for a browser workload.",
                    "Browser concurrency is set with browserUsers, not with load stages."));
        }
    }

    private static void validateStartConditions(List<WorkloadSpec> workloads,
            Set<String> workloadNames, List<ValidationFinding> findings) {
        for (int i = 0; i < workloads.size(); i++) {
            WorkloadSpec workload = workloads.get(i);
            var startWhen = workload.startWhen();
            if (startWhen == null) {
                continue;
            }
            String path = "/spec/workloads/" + i + "/startWhen";

            if (isBlank(startWhen.workload())) {
                findings.add(ValidationFinding.error("spec.workload.startWhen.workload.missing",
                        path + "/workload", "startWhen requires a workload name.", null));
            } else if (!workloadNames.contains(startWhen.workload())) {
                findings.add(ValidationFinding.error("spec.workload.startWhen.unresolved",
                        path + "/workload",
                        "startWhen references unknown workload '" + startWhen.workload() + "'.",
                        "Reference a workload declared in this specification."));
            } else if (startWhen.workload().equals(workload.name())) {
                findings.add(ValidationFinding.error("spec.workload.startWhen.selfReference",
                        path + "/workload",
                        "A workload cannot wait for itself.",
                        "Reference the workload whose steady state should trigger this one."));
            }
        }
    }

    private static void validateObjectives(PerformanceTestSpecification spec,
            List<ValidationFinding> findings) {
        List<ObjectiveSpec> objectives = spec.spec().objectives();

        if (objectives.isEmpty()) {
            findings.add(ValidationFinding.warning("spec.objective.missing", "/spec/objectives",
                    "No objectives are declared, so this test cannot pass or fail.",
                    "Declare at least a response-time and an error-rate objective."));
            return;
        }

        // Both scopes the platform can actually evaluate: a workload, and one scenario within a
        // workload as 'workload/scenario'. That second form is not a nicety — samplers are labelled
        // 'workload/scenario' precisely so a scenario-scoped objective resolves (defect D4's fix),
        // and a validator that refused the label the results carry would reject an objective the
        // evaluator handles perfectly well.
        Set<String> resolvableScopes = new HashSet<>();
        for (WorkloadSpec workload : spec.spec().workloads()) {
            if (workload.name() == null) {
                continue;
            }
            resolvableScopes.add(workload.name());
            workload.scenarios().stream()
                    .map(ScenarioSpec::name)
                    .filter(name -> name != null)
                    .forEach(name -> resolvableScopes.add(workload.name() + "/" + name));
        }

        for (int i = 0; i < objectives.size(); i++) {
            ObjectiveSpec objective = objectives.get(i);
            String path = "/spec/objectives/" + i;

            if (isBlank(objective.metric())) {
                findings.add(ValidationFinding.error("spec.objective.metric.missing",
                        path + "/metric", "An objective requires a metric.", null));
            }
            if (objective.threshold() == null) {
                findings.add(ValidationFinding.error("spec.objective.threshold.missing",
                        path + "/threshold",
                        "An objective requires a threshold.",
                        "For example 750ms for a latency objective, or 1% for an error rate."));
            }
            if (!isBlank(objective.scope()) && !resolvableScopes.contains(objective.scope())) {
                findings.add(ValidationFinding.error("spec.objective.scope.unresolved",
                        path + "/scope",
                        "Objective scope '" + objective.scope() + "' does not match any workload "
                                + "or scenario.",
                        "Scope an objective to a declared workload, to one scenario within it as "
                                + "'workload/scenario', or omit scope to apply it to the whole test."));
            }
            if (objective.percentile() != null
                    && (objective.percentile() < 1 || objective.percentile() > 100)) {
                findings.add(ValidationFinding.error("spec.objective.percentile.invalid",
                        path + "/percentile",
                        "A percentile must be between 1 and 100.", null));
            }
        }
    }

    private static void validateGovernance(PerformanceTestSpecification spec,
            List<ValidationFinding> findings) {
        var governance = spec.spec().governance();
        if (governance == null || governance.maxDuration() == null) {
            findings.add(ValidationFinding.warning("spec.governance.maxDuration.missing",
                    "/spec/governance/maxDuration",
                    "No maximum duration is declared, so the platform default applies.",
                    "Declare maxDuration to make the intended ceiling explicit."));
            return;
        }

        Duration declaredLongest = spec.spec().workloads().stream()
                .map(WorkloadSpec::totalDuration)
                .max(Duration::compareTo)
                .orElse(Duration.ZERO);

        if (declaredLongest.compareTo(governance.maxDuration()) > 0) {
            findings.add(ValidationFinding.error("spec.governance.maxDuration.exceeded",
                    "/spec/governance/maxDuration",
                    "The workload stages run longer than the declared maxDuration.",
                    "Either raise maxDuration or shorten the stages. The platform stops a run at "
                            + "its duration ceiling, which would truncate the test."));
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static void requireName(String value, String path, String label,
            List<ValidationFinding> findings) {
        if (isBlank(value)) {
            findings.add(ValidationFinding.error("spec.name.missing", path,
                    label + " is required.", null));
            return;
        }
        if (value.length() > MAX_NAME_LENGTH) {
            findings.add(ValidationFinding.error("spec.name.tooLong", path,
                    label + " must be at most " + MAX_NAME_LENGTH + " characters.",
                    "Names appear in artifact object keys and Kubernetes labels, which are "
                            + "length-limited."));
            return;
        }
        if (!NAME.matcher(value).matches()) {
            findings.add(ValidationFinding.error("spec.name.invalid", path,
                    label + " '" + value + "' must be lowercase alphanumeric with hyphens, "
                            + "starting and ending with an alphanumeric character.",
                    "Names are used in artifact object keys and Kubernetes labels."));
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
