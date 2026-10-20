package com.perfplatform.domain.validation;

import com.perfplatform.domain.dataset.DatasetBinding;
import com.perfplatform.domain.dataset.DatasetUri;
import com.perfplatform.domain.spec.PerformanceTestSpecification;
import com.perfplatform.domain.spec.PerformanceTestSpecification.AuthenticationSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.ObjectiveSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.ScenarioSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.AssertionOperator;
import com.perfplatform.domain.spec.PerformanceTestSpecification.AssertionSubject;
import com.perfplatform.domain.spec.PerformanceTestSpecification.ExtractionSource;
import com.perfplatform.domain.spec.PerformanceTestSpecification.StepSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.SourceSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.WorkloadSpec;
import com.perfplatform.domain.spec.SourceType;
import com.perfplatform.domain.spec.WorkloadType;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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

    /** Content-addressed artifact digest for uploaded or pasted source material. */
    private static final Pattern SHA256_DIGEST = Pattern.compile("^sha256:[0-9a-f]{64}$");

    /** Development-only source scheme: a path inside the runner's own checkout. */
    private static final String WORKSPACE_SCHEME = "workspace://";

    private static final int MAX_NAME_LENGTH = 63;

    /**
     * Below this implied response-time budget, a concurrency ceiling is worth mentioning.
     *
     * <p>A hundred milliseconds is the point where "only a cache lookup can keep up with this" stops
     * being an exaggeration. Above it the ceiling is plausible for an ordinary API and the platform
     * has nothing useful to say; below it, most targets will be throttled by the ceiling rather than
     * measured at the rate.
     */
    private static final long TIGHT_RESPONSE_BUDGET_MILLIS = 100;

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
        boolean hasWorkspace = isWorkspaceUri(source.uri());
        boolean hasArtifact = !isBlank(source.uri()) && !hasWorkspace;
        boolean hasGit = git != null;

        if (hasWorkspace) {
            validateWorkspaceSource(source.uri(), path + "/uri", findings);
            return;
        }

        if (!hasArtifact && !hasGit) {
            findings.add(ValidationFinding.error("spec.source.playwright.material.missing", path,
                    "A Playwright source requires either a stored artifact URI and digest, or git coordinates.",
                    "For this release, upload or paste the reviewed script and reference its "
                            + "artifact:// URI with a sha256 digest."));
            return;
        }

        if (hasArtifact) {
            validateSourceDigest(source.digest(), path + "/digest", findings);
        }

        if (!hasGit) {
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

    /** Whether a source URI names a path in the runner's own checkout rather than stored material. */
    public static boolean isWorkspaceUri(String uri) {
        return uri != null && uri.startsWith(WORKSPACE_SCHEME);
    }

    /** The path part of a {@code workspace://} URI. */
    public static String workspacePath(String uri) {
        return isWorkspaceUri(uri) ? uri.substring(WORKSPACE_SCHEME.length()) : null;
    }

    /**
     * Checks a development-only workspace source, and says plainly that it is not reproducible.
     *
     * <p>{@code workspace://tests/journey.spec.ts} names a file in the checkout the runner already
     * has. It exists so a browser journey can be executed from the platform while it is still being
     * written — the alternative is committing and pushing before every trial run, which is the kind
     * of friction that makes people go back to running {@code npx playwright test} by hand and stop
     * using the platform at all.
     *
     * <p>It is reported as a warning rather than accepted silently, because the file behind the path
     * can change between two runs of the same immutable definition version. That breaks the promise
     * the digest is supposed to make: the specification would be identical while the code that
     * executed was not, so a stored version no longer tells you what ran. Governance is expected to
     * refuse this for anything approved; the finding is what gives it something to refuse on.
     */
    private static void validateWorkspaceSource(String uri, String path,
            List<ValidationFinding> findings) {

        String relative = workspacePath(uri);
        if (isBlank(relative)) {
            findings.add(ValidationFinding.error("spec.source.workspace.path.missing", path,
                    "A workspace source requires a path, for example "
                            + "workspace://tests/journey.spec.ts.", null));
            return;
        }
        if (relative.contains("..") || relative.startsWith("/")) {
            // Same reasoning as the git path check: a path leaving the checkout would let a
            // specification read the runner's filesystem.
            findings.add(ValidationFinding.error("spec.source.workspace.path.traversal", path,
                    "The workspace path must stay inside the configured workspace root.",
                    "Remove '..' segments and use a path relative to the workspace root."));
            return;
        }

        findings.add(ValidationFinding.warning("spec.source.workspace.notReproducible", path,
                "Source '" + uri + "' reads a file from the runner's workspace, so what executes "
                        + "is not fixed by this definition version.",
                "Development only. Pin the reviewed script to a commit SHA with git coordinates "
                        + "before this definition is approved, so the code that ran is recoverable."));
    }

    private static void validateSourceDigest(String digest, String path,
            List<ValidationFinding> findings) {
        if (isBlank(digest)) {
            findings.add(ValidationFinding.error("spec.source.playwright.digest.missing", path,
                    "A stored Playwright source requires a SHA-256 digest.",
                    "Store the exact reviewed script as an immutable artifact and include "
                            + "digest: sha256:<64 lowercase hex characters>."));
            return;
        }
        if (!SHA256_DIGEST.matcher(digest.trim()).matches()) {
            findings.add(ValidationFinding.error("spec.source.playwright.digest.invalid", path,
                    "The Playwright source digest must be written as sha256:<64 lowercase hex characters>.",
                    "Recompute the digest over the exact uploaded or pasted script content."));
        }
    }

    private static void validateAuthentication(PerformanceTestSpecification spec,
            List<ValidationFinding> findings) {
        var authentications = spec.spec().authentication();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < authentications.size(); i++) {
            var authentication = authentications.get(i);
            String path = "/spec/authentication/" + i;

            requireName(authentication.name(), path + "/name", "authentication name", findings);
            if (authentication.name() != null && !seen.add(authentication.name())) {
                findings.add(ValidationFinding.error("spec.authentication.name.duplicate",
                        path + "/name",
                        "Duplicate authentication name '" + authentication.name() + "'.",
                        "Authentication names must be unique so workloads can reference them."));
            }
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
        Map<String, AuthenticationSpec> authenticationsByName = spec.spec().authentication().stream()
                .filter(authentication -> authentication.name() != null)
                .collect(java.util.stream.Collectors.toMap(
                        AuthenticationSpec::name,
                        java.util.function.Function.identity(),
                        (left, right) -> left));

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

            validateAuthRef(workload, path, authenticationsByName, findings);

            if (workload.type() == WorkloadType.PROTOCOL) {
                validateProtocolWorkload(workload, path, declaredColumns(spec), findings);
            } else {
                validateBrowserWorkload(workload, path, findings);
            }
        }

        validateStartConditions(workloads, workloadNames, findings);
    }

    private static void validateAuthRef(WorkloadSpec workload, String path,
            Map<String, AuthenticationSpec> authenticationsByName,
            List<ValidationFinding> findings) {
        if (isBlank(workload.authRef())) {
            return;
        }
        AuthenticationSpec authentication = authenticationsByName.get(workload.authRef());
        if (authentication == null) {
            findings.add(ValidationFinding.error("spec.workload.authRef.unresolved",
                    path + "/authRef",
                    "authRef '" + workload.authRef()
                            + "' does not match any declared authentication.",
                    "Declare it under spec.authentication, or correct the reference."));
            return;
        }
        if (isBlank(authentication.injectAs())) {
            findings.add(ValidationFinding.error("spec.authentication.injectAs.missing",
                    path + "/authRef",
                    "Authentication '" + workload.authRef()
                            + "' does not say how the runner should inject it.",
                    "Set injectAs to the HTTP header name to add, for example Authorization "
                            + "or Cookie."));
        } else if (authentication.injectAs().contains("\n")
                || authentication.injectAs().contains("\r")) {
            findings.add(ValidationFinding.error("spec.authentication.injectAs.invalid",
                    path + "/authRef",
                    "Authentication '" + workload.authRef()
                            + "' has an invalid injection target.",
                    "Use a single HTTP header name, for example Authorization or Cookie."));
        }
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
                        "Set type: stages for a fixed number of concurrent users, or "
                                + "type: arrival-rate for a fixed number of requests per second."));
            }

            boolean arrivalRate = workload.model().isArrivalRate();
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
                if (arrivalRate) {
                    validateArrivalRateStage(stage, stagePath, findings);
                } else {
                    validateStagedStage(stage, stagePath, findings);
                }
            }

            if (arrivalRate) {
                validateConcurrencyCeiling(workload, path, findings);
            } else if (workload.peakUsers() == 0) {
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

        if (workload.browserProfile() != null) {
            findings.add(ValidationFinding.warning("spec.workload.browserProfile.ignored",
                    path + "/browserProfile",
                    "browserProfile is ignored for a protocol workload.",
                    "Move it to a browser workload, or remove it from this workload."));
        }
    }

    /** A closed-model stage: users are the target, and a rate would not be read. */
    private static void validateStagedStage(PerformanceTestSpecification.LoadStage stage,
            String stagePath, List<ValidationFinding> findings) {

        if (stage.targetUsers() < 0) {
            findings.add(ValidationFinding.error("spec.workload.stage.users.negative",
                    stagePath + "/targetUsers",
                    "targetUsers must not be negative.", null));
        }
        // Refused rather than ignored. A stage carrying a rate in a closed model was written by
        // somebody who believes the run will send that many requests a second, and it will not —
        // it will send whatever the users can. Silently dropping the field would leave them
        // reading a result against an expectation the platform never agreed to.
        if (stage.targetRate() != null) {
            findings.add(ValidationFinding.error("spec.workload.stage.rate.notApplicable",
                    stagePath + "/targetRate",
                    "targetRate has no meaning in a stages model, where throughput is whatever "
                            + "the declared users produce.",
                    "Set type: arrival-rate to ask for a rate, or remove targetRate and state the "
                            + "users you want."));
        }
    }

    /** An open-model stage: the rate is the target, and a user count would not be read. */
    private static void validateArrivalRateStage(PerformanceTestSpecification.LoadStage stage,
            String stagePath, List<ValidationFinding> findings) {

        if (stage.targetRate() == null) {
            findings.add(ValidationFinding.error("spec.workload.stage.rate.missing",
                    stagePath + "/targetRate",
                    "An arrival-rate stage requires targetRate.",
                    "State the requests per second this stage should send. A final stage with "
                            + "targetRate: 0 expresses ramp-down."));
        } else if (stage.targetRate() < 0) {
            findings.add(ValidationFinding.error("spec.workload.stage.rate.negative",
                    stagePath + "/targetRate",
                    "targetRate must not be negative.", null));
        }
        if (stage.targetUsers() != 0) {
            findings.add(ValidationFinding.error("spec.workload.stage.users.notApplicable",
                    stagePath + "/targetUsers",
                    "targetUsers has no meaning in an arrival-rate model, where concurrency is "
                            + "whatever the target's latency produces.",
                    "Remove targetUsers. The ceiling on concurrency is model.maxConcurrency."));
        }
    }

    /**
     * The ceiling an open model cannot run without.
     *
     * <p>Three findings, in descending order of how badly things go without them.
     *
     * <p><b>No ceiling at all is an error.</b> In an open model a stalled target accumulates
     * in-flight requests without bound: arrivals keep producing them and nothing completes. The
     * generator exhausts its threads or its heap, dies, and the run reports the generator's failure
     * as the target's — a result that looks like evidence and is not.
     *
     * <p><b>A ceiling that implies an unreachable response time is a warning.</b> The relationship
     * is Little's law: sustaining a rate {@code R} with at most {@code C} requests in flight
     * requires every response to arrive within {@code C / R} seconds. A ceiling of 20 against 400
     * requests a second means 50 ms — achievable for a cache lookup and not for much else, and if
     * the target is slower then the ceiling rather than the rate is what limits the run, which
     * measures the platform's own throttle rather than the system.
     *
     * <p>The finding states the implied budget rather than prescribing a ceiling, because only the
     * author knows how fast their endpoint is. A warning, not an error: throttling deliberately is
     * a legitimate thing to want.
     *
     * <p><b>Every stage at zero is an error</b>, for the same reason it is in a closed model: the
     * workload would generate nothing.
     */
    private static void validateConcurrencyCeiling(WorkloadSpec workload, String path,
            List<ValidationFinding> findings) {

        Integer ceiling = workload.model().maxConcurrency();
        int peakRate = workload.peakArrivalRate();

        if (ceiling == null) {
            findings.add(ValidationFinding.error("spec.workload.model.maxConcurrency.missing",
                    path + "/model/maxConcurrency",
                    "An arrival-rate model requires maxConcurrency.",
                    "Set the most requests you are willing to have in flight at once. Without it, "
                            + "a target that stalls makes this run accumulate requests until the "
                            + "load generator itself fails — and the run would report that as the "
                            + "target's failure."));
        } else if (ceiling < 1) {
            findings.add(ValidationFinding.error("spec.workload.model.maxConcurrency.invalid",
                    path + "/model/maxConcurrency",
                    "maxConcurrency must be at least 1.", null));
        } else if (peakRate > 0) {
            long impliedBudgetMillis = (ceiling * 1000L) / peakRate;
            if (impliedBudgetMillis < TIGHT_RESPONSE_BUDGET_MILLIS) {
                findings.add(ValidationFinding.warning(
                        "spec.workload.model.maxConcurrency.tight",
                        path + "/model/maxConcurrency",
                        "With maxConcurrency " + ceiling + " and a peak of " + peakRate
                                + "/s, this run can only reach its rate if every response arrives "
                                + "within " + impliedBudgetMillis + "ms.",
                        "If the target is slower than that, the ceiling rather than the rate is "
                                + "what limits the test. Raise it to roughly rate × the slowest "
                                + "response you expect, in seconds — or leave it if throttling is "
                                + "what you intended."));
            }
        }

        if (peakRate == 0) {
            findings.add(ValidationFinding.error("spec.workload.stage.rate.allZero",
                    path + "/model/stages",
                    "Every stage targets zero requests per second, so this workload generates no "
                            + "load.",
                    "Set a positive targetRate on at least one stage."));
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

        // A journey states its requests in its steps, so none of the single-request checks below
        // apply to it. Its own rules run first and unconditionally: an early return that skipped
        // them would leave a journey's headers un-scanned for credentials, which is exactly the
        // guard this increment had to avoid weakening.
        if (scenario.isJourney()) {
            validateSteps(scenario, scenarioPath, datasetColumns, findings);

            // Both forms at once is refused rather than resolved by a precedence rule. A rule would
            // silently ignore whichever half the author actually meant, and the one it ignored is
            // the one they would go looking for when the results made no sense.
            if (hasPath || hasOperation) {
                findings.add(ValidationFinding.error("spec.scenario.request.ambiguous",
                        scenarioPath,
                        "A scenario states both a single request and a list of steps.",
                        "Keep one. A journey's requests belong in 'steps'; remove the scenario's "
                                + "own method, path and operationId."));
            }
            return;
        }

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

        validateHeaders(scenario.headers(), scenarioPath, findings);
        validateQuery(scenario.query(), scenarioPath, findings);
        validateBody(scenario.body(), scenario.method(), scenarioPath, findings);
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
    private static void validateHeaders(Map<String, String> headers, String scenarioPath,
            List<ValidationFinding> findings) {
        validateHeaders(headers, scenarioPath, Set.of(), findings);
    }

    /**
     * The same checks, but aware of values the platform will supply at run time.
     *
     * <p>{@code supplied} holds names a dataset column or an earlier extraction provides. A header
     * named as a credential whose value is <em>only</em> such a reference is permitted, and this is
     * the one place the guard bends — for a reason that keeps its purpose intact rather than
     * weakening it.
     *
     * <p>The guard exists so a <b>secret value</b> is never written into a document that is stored,
     * digested, committed to Git and echoed back by the validate endpoint. {@code Bearer ${session}}
     * contains no secret: it is a placeholder for something extracted during the run, and the
     * document is exactly as safe to store with it as without. Refusing it would make correlation
     * useless for the case it exists to serve — carrying a session forward — and the workaround
     * people would find is worse than the rule.
     *
     * <p>Two things keep this narrow. The reference must resolve, which the unresolved-reference
     * check enforces separately, so a header cannot smuggle in a name nothing provides. And the text
     * around the reference is still scanned: {@code Bearer abc123…${x}} is refused on the literal
     * part, because a reference next to a secret does not launder it.
     */
    private static void validateHeaders(Map<String, String> headers, String scenarioPath,
            Set<String> supplied, List<ValidationFinding> findings) {

        headers.forEach((name, value) -> {
            String path = scenarioPath + "/headers/" + name;

            if (isBlank(name)) {
                findings.add(ValidationFinding.error("spec.scenario.header.name.missing",
                        scenarioPath + "/headers", "A header name must not be blank.", null));
                return;
            }

            if (SecretValueDetector.isCredentialHeader(name) && suppliedAtRunTime(value, supplied)) {
                // A placeholder for a value the run produces, not a secret in the document.
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
     * Whether a value is a reference to something the platform supplies, and nothing sensitive else.
     *
     * <p>Requires at least one reference, requires every reference to be a name the run will
     * actually provide, and requires whatever text surrounds them to be innocuous. "Bearer " is
     * innocuous; a token sitting beside a reference is not.
     */
    private static boolean suppliedAtRunTime(String value, Set<String> supplied) {
        if (value == null || !DatasetBinding.hasReference(value)) {
            return false;
        }
        Set<String> referenced = DatasetBinding.referencesIn(Map.of("v", value));
        if (referenced.isEmpty() || !supplied.containsAll(referenced)) {
            return false;
        }
        // What is left once the placeholders are removed. A reference does not launder the text
        // beside it.
        String literal = value.replaceAll("\\$\\{[^}]*}", " ").trim();
        return !SecretValueDetector.looksLikeCredential(literal);
    }

    /**
     * Query parameters must not carry credentials either — and for a sharper reason than headers.
     *
     * <p>A query string is recorded by every proxy, load balancer and access log between the runner
     * and the target, and lands in browser history and referrer headers besides. A credential in one
     * is disclosed to systems nobody enumerated (CLAUDE.md §20).
     */
    private static void validateQuery(Map<String, String> query, String scenarioPath,
            List<ValidationFinding> findings) {

        query.forEach((name, value) -> {
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
    private static void validateBody(ScenarioSpec.RequestBody body, String method,
            String scenarioPath, List<ValidationFinding> findings) {

        if (body == null || body.isEmpty()) {
            return;
        }

        String effectiveMethod = method == null || method.isBlank()
                ? "GET" : method.trim().toUpperCase(java.util.Locale.ROOT);
        if ("GET".equals(effectiveMethod) || "HEAD".equals(effectiveMethod)) {
            findings.add(ValidationFinding.error("spec.scenario.body.notAllowed",
                    scenarioPath + "/body",
                    "A " + effectiveMethod + " request cannot carry a body.",
                    "Many servers and proxies drop it silently, so the test would measure a "
                            + "different request than the one written. Use query parameters, or a "
                            + "method that carries a body."));
        }

        if (SecretValueDetector.looksLikeCredential(body.content())) {
            findings.add(ValidationFinding.error("spec.scenario.body.inline-secret",
                    scenarioPath + "/body/content",
                    "The request body appears to contain a credential value.",
                    "Specifications hold secret references, never secret values."));
        }
    }

    /**
     * A journey's steps, and the values that flow between them.
     *
     * <p>Three classes of rule, and the first is the one that must not be forgotten: <b>every step's
     * headers, query and body go through exactly the same credential checks a single-request
     * scenario does.</b> Steps were a new way to write a request, and a new way to write a request
     * is a new way to smuggle an {@code Authorization} header past a guard that only knew about the
     * old one.
     *
     * <p>Then the shape of each step, then the {@code ${...}} namespace: a reference resolves
     * against the dataset columns plus whatever an <em>earlier</em> step extracted. Later is not
     * good enough — an unfilled reference is sent as literal text, every request fails, and the run
     * reports fast, healthy-looking latency for a target that did no work.
     */
    private static void validateSteps(ScenarioSpec scenario, String scenarioPath,
            Set<String> datasetColumns, List<ValidationFinding> findings) {

        List<StepSpec> steps = scenario.steps();
        if (steps.isEmpty()) {
            return;
        }

        Set<String> namesSoFar = new java.util.LinkedHashSet<>(datasetColumns);
        Set<String> extractedNames = new java.util.LinkedHashSet<>();

        for (int i = 0; i < steps.size(); i++) {
            StepSpec step = steps.get(i);
            String stepPath = scenarioPath + "/steps/" + i;

            if (isBlank(step.name())) {
                findings.add(ValidationFinding.error("spec.step.name.missing", stepPath + "/name",
                        "Every step needs a name.",
                        "It identifies the step in results and findings, so 'sign-in' beats 'step 2'."));
            }

            if (isBlank(step.path())) {
                findings.add(ValidationFinding.error("spec.step.path.missing", stepPath + "/path",
                        "A step must declare a path.", "For example 'path: /api/orders'."));
            } else {
                String requestPath = step.path().trim();
                if (!requestPath.startsWith("/")) {
                    findings.add(ValidationFinding.error("spec.step.path.invalid", stepPath + "/path",
                            "A step path must begin with '/'.",
                            "Paths are relative to the base URL the platform resolves for this "
                                    + "application. Write '/api/users', not a full URL."));
                } else if (requestPath.contains("://")) {
                    findings.add(ValidationFinding.error("spec.step.path.absolute", stepPath + "/path",
                            "A step path must not be a full URL.",
                            "Where a run's traffic goes is resolved from the team's registered "
                                    + "targets. A specification cannot name its own host."));
                }
            }

            if (step.method() != null && !step.method().isBlank()
                    && !SUPPORTED_METHODS.contains(
                            step.method().trim().toUpperCase(java.util.Locale.ROOT))) {
                findings.add(ValidationFinding.error("spec.step.method.unsupported",
                        stepPath + "/method",
                        "HTTP method '" + step.method() + "' is not supported.",
                        "Supported methods are " + SUPPORTED_METHODS.stream().sorted().toList() + "."));
            }

            // The same guards, on the same terms — plus the names the run will have supplied by
            // the time this step executes, so a session carried in an Authorization header is
            // recognised as a placeholder rather than as a secret. See validateHeaders' javadoc.
            validateHeaders(step.headers(), stepPath, namesSoFar, findings);
            validateQuery(step.query(), stepPath, findings);
            validateBody(step.body(), step.method(), stepPath, findings);

            validateStepReferences(step, stepPath, namesSoFar, datasetColumns, findings);
            validateExtractions(step, stepPath, datasetColumns, extractedNames, findings);
            validateAssertions(step, stepPath, findings);

            step.extract().forEach(extraction -> namesSoFar.add(extraction.name()));
        }
    }

    /**
     * Every {@code ${...}} a step uses must already exist when the step runs.
     *
     * <p>"Already" is the whole rule. A reference to a value a <em>later</em> step extracts is not a
     * timing subtlety — it is a request sent with the characters {@code ${session}} in its header.
     */
    private static void validateStepReferences(StepSpec step, String stepPath,
            Set<String> available, Set<String> datasetColumns, List<ValidationFinding> findings) {

        for (String reference : DatasetBinding.referencesIn(step)) {
            if (available.contains(reference)) {
                continue;
            }
            findings.add(ValidationFinding.error("spec.step.reference.unresolved",
                    stepPath,
                    "This step uses ${" + reference + "}, which nothing has provided yet.",
                    datasetColumns.isEmpty()
                            ? "Extract it in an earlier step, or declare a dataset column named '"
                                    + reference + "'."
                            : "Extract it in an earlier step, or add it to the dataset. Declared "
                                    + "columns are " + datasetColumns.stream().sorted().toList()
                                    + ". An unfilled reference is sent as the literal text "
                                    + "${" + reference + "}, so every request would fail."));
        }
    }

    private static void validateExtractions(StepSpec step, String stepPath,
            Set<String> datasetColumns, Set<String> alreadyExtracted,
            List<ValidationFinding> findings) {

        for (int e = 0; e < step.extract().size(); e++) {
            var extraction = step.extract().get(e);
            String path = stepPath + "/extract/" + e;

            if (isBlank(extraction.name())) {
                findings.add(ValidationFinding.error("spec.extract.name.missing", path + "/name",
                        "An extracted value needs a name.",
                        "Later steps refer to it as ${name}."));
                continue;
            }

            // A name that is both a column and an extraction has two sources, and which one wins
            // would depend on evaluation order — a test whose behaviour nobody can predict from
            // reading it.
            if (datasetColumns.contains(extraction.name())) {
                findings.add(ValidationFinding.error("spec.extract.name.collides-with-dataset",
                        path + "/name",
                        "'" + extraction.name() + "' is already a dataset column, so ${"
                                + extraction.name() + "} would have two sources.",
                        "Rename one of them. Which value a request used would otherwise depend on "
                                + "evaluation order rather than on anything the document says."));
            }
            if (!alreadyExtracted.add(extraction.name())) {
                findings.add(ValidationFinding.error("spec.extract.name.duplicate", path + "/name",
                        "'" + extraction.name() + "' is extracted more than once in this journey.",
                        "Rename one of them, so a step's ${" + extraction.name() + "} refers to "
                                + "exactly one thing."));
            }

            if (extraction.from() == null) {
                findings.add(ValidationFinding.error("spec.extract.from.missing", path + "/from",
                        "An extracted value must say where to look.",
                        "One of json-body, regex-body, header, set-cookie or status."));
                continue;
            }

            boolean needsExpression = extraction.from() != ExtractionSource.STATUS;
            if (needsExpression && isBlank(extraction.expression())) {
                findings.add(ValidationFinding.error("spec.extract.expression.missing",
                        path + "/expression",
                        "Extracting from " + extraction.from().name().toLowerCase(java.util.Locale.ROOT).replace('_', '-') + " needs an expression.",
                        switch (extraction.from()) {
                            case JSON_BODY -> "A JSON path, such as '$.token'.";
                            case REGEX_BODY -> "A regular expression with one capturing group.";
                            case HEADER -> "The header's name.";
                            case SET_COOKIE -> "The cookie's name.";
                            case STATUS -> null;
                        }));
            }

            if (extraction.from() == ExtractionSource.REGEX_BODY
                    && !isBlank(extraction.expression())) {
                validateCapturingGroup(extraction.expression(), path + "/expression", findings);
            }
        }
    }

    /**
     * A body regular expression must compile, and must capture exactly one group.
     *
     * <p>Zero groups extracts nothing and every request afterwards carries an empty value. Two
     * groups is ambiguous, and the engine would pick one — silently, and not necessarily the one
     * that was meant.
     */
    private static void validateCapturingGroup(String expression, String path,
            List<ValidationFinding> findings) {
        try {
            int groups = java.util.regex.Pattern.compile(expression).matcher("").groupCount();
            if (groups != 1) {
                findings.add(ValidationFinding.error("spec.extract.expression.groups", path,
                        "A body pattern must have exactly one capturing group; this has "
                                + groups + ".",
                        "Wrap the part to extract in parentheses, and use (?:...) for any other "
                                + "grouping."));
            }
        } catch (java.util.regex.PatternSyntaxException e) {
            // The message is the engine's own and names the position, which is what makes a broken
            // pattern fixable. It quotes the pattern, never a response.
            findings.add(ValidationFinding.error("spec.extract.expression.invalid", path,
                    "This is not a valid regular expression.", e.getDescription()));
        }
    }

    private static void validateAssertions(StepSpec step, String stepPath,
            List<ValidationFinding> findings) {

        for (int a = 0; a < step.expect().size(); a++) {
            var assertion = step.expect().get(a);
            String path = stepPath + "/expect/" + a;

            if (assertion.subject() == null || assertion.operator() == null) {
                findings.add(ValidationFinding.error("spec.expect.incomplete", path,
                        "An expectation needs both a subject and an operator.",
                        "For example: subject: status, operator: equals, value: '200'."));
                continue;
            }
            if (isBlank(assertion.value())) {
                findings.add(ValidationFinding.error("spec.expect.value.missing", path + "/value",
                        "An expectation needs a value to compare against.", null));
                continue;
            }
            if (assertion.subject() == AssertionSubject.STATUS
                    && assertion.operator() == AssertionOperator.EQUALS) {
                try {
                    int code = Integer.parseInt(assertion.value().trim());
                    if (code < 100 || code > 599) {
                        throw new NumberFormatException();
                    }
                } catch (NumberFormatException e) {
                    findings.add(ValidationFinding.error("spec.expect.status.invalid",
                            path + "/value",
                            "'" + assertion.value() + "' is not an HTTP status code.",
                            "Use a number between 100 and 599, such as 200 or 201."));
                }
            }
            if (assertion.operator() == AssertionOperator.MATCHES) {
                try {
                    java.util.regex.Pattern.compile(assertion.value());
                } catch (java.util.regex.PatternSyntaxException e) {
                    findings.add(ValidationFinding.error("spec.expect.pattern.invalid",
                            path + "/value",
                            "This is not a valid regular expression.", e.getDescription()));
                }
            }
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

        validateBrowserProfile(workload, path, findings);
    }

    private static void validateBrowserProfile(WorkloadSpec workload, String path,
            List<ValidationFinding> findings) {
        var profile = workload.browserProfile();
        if (profile == null) {
            return;
        }
        if (isBlank(profile.device())) {
            findings.add(ValidationFinding.error("spec.workload.browserProfile.device.missing",
                    path + "/browserProfile/device",
                    "A browser profile device must not be blank.",
                    "Use mobile, desktop, or a named Playwright device profile."));
        }
        if (isBlank(profile.network())) {
            findings.add(ValidationFinding.error("spec.workload.browserProfile.network.missing",
                    path + "/browserProfile/network",
                    "A browser profile network must not be blank.",
                    "Use slow-4g, fast-4g, wifi, or none."));
        } else {
            String network = profile.network().trim();
            if (!Set.of("slow-4g", "fast-4g", "wifi", "none").contains(network)) {
                findings.add(ValidationFinding.error(
                        "spec.workload.browserProfile.network.unsupported",
                        path + "/browserProfile/network",
                        "Browser network profile '" + profile.network() + "' is not supported.",
                        "Supported profiles are slow-4g, fast-4g, wifi, and none."));
            }
        }
        if (profile.cpuThrottle() != null && profile.cpuThrottle() <= 0) {
            findings.add(ValidationFinding.error(
                    "spec.workload.browserProfile.cpuThrottle.invalid",
                    path + "/browserProfile/cpuThrottle",
                    "cpuThrottle must be greater than zero.", null));
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
            }
            if (isBlank(startWhen.stage())) {
                findings.add(ValidationFinding.error("spec.workload.startWhen.stage.missing",
                        path + "/stage", "startWhen requires a stage name.", null));
                continue;
            }
            if (isBlank(startWhen.workload())) {
                continue;
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
            } else if (!stageExists(workloads, startWhen.workload(), startWhen.stage())) {
                findings.add(ValidationFinding.error("spec.workload.startWhen.stage.unresolved",
                        path + "/stage",
                        "startWhen references stage '" + startWhen.stage() + "' on workload '"
                                + startWhen.workload() + "', but that stage is not declared.",
                        "Name the protocol load stage to wait for, for example "
                                + "name: steady-state."));
            }
        }
    }

    private static boolean stageExists(List<WorkloadSpec> workloads, String workloadName,
            String stageName) {
        if (isBlank(stageName)) {
            return false;
        }
        return workloads.stream()
                .filter(candidate -> workloadName.equals(candidate.name()))
                .map(WorkloadSpec::model)
                .filter(java.util.Objects::nonNull)
                .flatMap(model -> model.stages().stream())
                .map(PerformanceTestSpecification.LoadStage::name)
                .anyMatch(stageName::equals);
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
            findings.addAll(ObjectiveContract.validate(objective, path).findings());
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
