package com.perfplatform.orchestration.run;

import com.perfplatform.domain.identity.TestDefinitionVersionId;
import com.perfplatform.domain.identity.TestRunId;
import com.perfplatform.domain.execution.RuntimeCredential;
import com.perfplatform.domain.port.DefinitionVersionCatalog;
import com.perfplatform.domain.port.DefinitionVersionCatalog.DefinitionVersion;
import com.perfplatform.domain.port.RuntimeCredentialStore;
import com.perfplatform.domain.port.TestRunRepository;
import com.perfplatform.domain.run.RunEvent;
import com.perfplatform.domain.run.RunEventType;
import com.perfplatform.domain.run.RunState;
import com.perfplatform.domain.run.RunTransition;
import com.perfplatform.domain.run.TestRun;
import com.perfplatform.domain.security.CallerContext;
import com.perfplatform.domain.security.Scope;
import com.perfplatform.domain.spec.PerformanceTestSpecification;
import com.perfplatform.domain.admission.ExecutionLimits;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Requests a run of an immutable definition version.
 *
 * <p>Returns as soon as the request is durable. It does not wait for the test, provision anything,
 * or generate load — a performance test lasts minutes to hours, and a trigger that blocked would
 * hold an HTTP connection open for the duration and fail a CI job on any proxy timeout.
 *
 * <h2>Idempotency</h2>
 * Every trigger carries a key. Submitting the same key twice returns the same run rather than
 * starting a second one, which matters more here than in most APIs: the duplicate would not be a
 * wasted row but a second load test against a real system, doubling the traffic and invalidating
 * both sets of results. The guarantee is enforced by a unique constraint, not by a read-then-write
 * check, because two replicas can pass the same check simultaneously.
 *
 * <p>A key reused with a <em>different</em> version is rejected rather than treated as a replay.
 * Silently returning the first run would tell a caller their new test had been accepted when it had
 * not, and they would read the old run's results as if they described the new one.
 */
public final class TriggerTestRunUseCase {

    private static final Logger log = LoggerFactory.getLogger(TriggerTestRunUseCase.class);

    private final TestRunRepository runs;
    private final DefinitionVersionCatalog versions;
    private final RuntimeCredentialStore runtimeCredentials;
    private final int maxBrowserSessionsPerRun;
    private final ExecutionLimits executionLimits;
    private final Clock clock;

    public TriggerTestRunUseCase(TestRunRepository runs, DefinitionVersionCatalog versions,
            RuntimeCredentialStore runtimeCredentials, Clock clock) {
        this(runs, versions, runtimeCredentials, 20, clock);
    }

    public TriggerTestRunUseCase(TestRunRepository runs, DefinitionVersionCatalog versions,
            RuntimeCredentialStore runtimeCredentials, int maxBrowserSessionsPerRun, Clock clock) {
        this(runs, versions, runtimeCredentials, maxBrowserSessionsPerRun, ExecutionLimits.DEFAULTS, clock);
    }

    public TriggerTestRunUseCase(TestRunRepository runs, DefinitionVersionCatalog versions,
            RuntimeCredentialStore runtimeCredentials, int maxBrowserSessionsPerRun,
            ExecutionLimits executionLimits, Clock clock) {
        this.runs = Objects.requireNonNull(runs, "runs");
        this.versions = Objects.requireNonNull(versions, "versions");
        this.runtimeCredentials = Objects.requireNonNull(runtimeCredentials, "runtimeCredentials");
        this.executionLimits = Objects.requireNonNull(executionLimits, "executionLimits");
        if (maxBrowserSessionsPerRun < 1) {
            throw new IllegalArgumentException("maxBrowserSessionsPerRun must be at least 1");
        }
        this.maxBrowserSessionsPerRun = maxBrowserSessionsPerRun;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * @param definitionVersionId the immutable version to execute
     * @param idempotencyKey      caller-supplied, unique within the team
     */
    public record TriggerCommand(TestDefinitionVersionId definitionVersionId, String idempotencyKey,
            Map<String, String> credentials) {
        public TriggerCommand {
            Objects.requireNonNull(definitionVersionId, "definitionVersionId");
            if (idempotencyKey == null || idempotencyKey.isBlank()) {
                throw new IllegalArgumentException("An idempotency key is required");
            }
            idempotencyKey = idempotencyKey.trim();
            if (idempotencyKey.length() > 200) {
                throw new IllegalArgumentException("Idempotency key must be at most 200 characters");
            }
            credentials = credentials == null ? Map.of() : Map.copyOf(credentials);
        }

        /** The shape that predates runtime credentials. */
        public TriggerCommand(TestDefinitionVersionId definitionVersionId, String idempotencyKey) {
            this(definitionVersionId, idempotencyKey, Map.of());
        }
    }

    /**
     * @param run      the run, newly created or the one the key already referred to
     * @param replayed whether this was a duplicate of an earlier trigger; the caller reports it so
     *                 a pipeline can tell "started" from "already started" without guessing
     */
    public record TriggerResult(TestRun run, boolean replayed) {
    }

    /** Raised when the referenced version does not exist. */
    public static class UnknownDefinitionVersionException extends RuntimeException {
        public UnknownDefinitionVersionException(TestDefinitionVersionId id) {
            super("No such test definition version: " + id);
        }
    }

    /** Raised when the version exists but must not be executed. */
    public static class VersionNotExecutableException extends RuntimeException {
        public VersionNotExecutableException(TestDefinitionVersionId id) {
            super("Version " + id + " did not pass validation and cannot be executed. "
                    + "Fix the specification and create a new version.");
        }
    }

    /** Raised when a version requires a runtime credential the trigger did not supply. */
    public static class RuntimeCredentialMissingException extends RuntimeException {
        public RuntimeCredentialMissingException(String name) {
            super("Runtime credential '" + name + "' was required but not supplied");
        }
    }

    /** Raised when a definition asks for more concurrent browser sessions than policy permits. */
    public static class BrowserSessionBudgetExceededException extends RuntimeException {
        private final transient int requested;
        private final transient int limit;

        public BrowserSessionBudgetExceededException(int requested, int limit) {
            super("This run asks for " + requested + " browser sessions, but this deployment allows "
                    + "at most " + limit + " per run.");
            this.requested = requested;
            this.limit = limit;
        }

        public int requested() {
            return requested;
        }

        public int limit() {
            return limit;
        }
    }

    public TriggerResult trigger(TriggerCommand command, CallerContext caller) {
        DefinitionVersion version = versions.find(command.definitionVersionId())
                .orElseThrow(() -> new UnknownDefinitionVersionException(command.definitionVersionId()));

        // Authorization before anything else observable. The team comes from the version, never
        // from the request: a caller states what to run, not who to bill it to.
        caller.requireAct(Scope.RUNS_EXECUTE, version.teamId());

        if (!version.executable()) {
            throw new VersionNotExecutableException(version.id());
        }
        enforceBrowserBudget(version.specification());

        List<RuntimeCredential> material = resolveRuntimeCredentials(
                version.specification(), command.credentials());

        String fingerprint = fingerprint(command);
        Instant now = clock.instant();

        return runs.findByIdempotencyKey(version.teamId(), command.idempotencyKey())
                .map(existing -> replay(existing, command, fingerprint, material, caller, now))
                .orElseGet(() -> create(command, version, fingerprint, material, caller, now));
    }

    private void enforceBrowserBudget(PerformanceTestSpecification specification) {
        int requested = specification.peakBrowserSessions();
        if (requested > maxBrowserSessionsPerRun) {
            throw new BrowserSessionBudgetExceededException(requested, maxBrowserSessionsPerRun);
        }
    }

    private TriggerResult replay(TestRun existing, TriggerCommand command, String fingerprint,
            List<RuntimeCredential> material, CallerContext caller, Instant now) {

        if (!existing.trigger().requestDigest().equals(fingerprint)) {
            throw new TestRunRepository.IdempotencyConflictException(
                    command.idempotencyKey(), existing.id());
        }

        // Worth recording: a duplicate trigger means the caller did not see the first response, and
        // that is useful when reconstructing what a pipeline did. Bounded by real retries — status
        // polling uses a read endpoint and appends nothing.
        runs.appendEvent(RunEvent.of(existing.id(), RunEventType.IDEMPOTENT_REPLAY,
                caller.subject(), RunEvent.detail("idempotencyKey", command.idempotencyKey()), now));
        runtimeCredentials.put(existing.id(), material);

        log.info("Idempotent replay of trigger runId={} teamId={} definitionVersionId={} subject={}",
                existing.id(), existing.teamId(), command.definitionVersionId(), caller.subject());

        return new TriggerResult(existing, true);
    }

    private TriggerResult create(TriggerCommand command, DefinitionVersion version,
            String fingerprint, List<RuntimeCredential> material, CallerContext caller, Instant now) {

        executionLimits.require(version.specification());

        RunTransition requested = TestRun.request(
                TestRunId.generate(),
                version.teamId(),
                version.id(),
                environmentOf(version.specification()),
                new TestRun.TriggerContext(command.idempotencyKey(), fingerprint, caller.subject()),
                now);

        TestRun created = runs.create(requested);
        runtimeCredentials.put(created.id(), material);

        // A concurrent replica may have won the constraint race, in which case create() returned
        // the run it made. That is a replay, not a new run, and reporting it as new would tell the
        // caller a second test had started.
        if (!created.id().equals(requested.run().id())) {
            if (!created.trigger().requestDigest().equals(fingerprint)) {
                throw new TestRunRepository.IdempotencyConflictException(
                        command.idempotencyKey(), created.id());
            }
            return new TriggerResult(created, true);
        }

        // The run exists and is durable; queueing it is a separate transaction. If the process dies
        // between the two, reconciliation finds a run stuck in VALIDATING and decides it there —
        // which is why that sweep exists rather than being defensive clutter.
        TestRun queued = runs.apply(created.transitionTo(
                RunState.QUEUED, caller.subject(), "Specification validated", now));

        log.info("Run queued runId={} teamId={} definitionVersionId={} subject={} idempotencyKey={}",
                queued.id(), queued.teamId(), version.id(), caller.subject(), command.idempotencyKey());

        return new TriggerResult(queued, false);
    }

    private static String environmentOf(PerformanceTestSpecification specification) {
        if (specification.spec() == null || specification.spec().environment() == null
                || specification.spec().environment().name() == null) {
            return "unspecified";
        }
        return specification.spec().environment().name();
    }

    private static List<RuntimeCredential> resolveRuntimeCredentials(
            PerformanceTestSpecification specification, Map<String, String> supplied) {
        if (specification.spec() == null) {
            return List.of();
        }

        Map<String, PerformanceTestSpecification.AuthenticationSpec> authByName =
                specification.spec().authentication().stream()
                        .filter(auth -> auth.name() != null)
                        .collect(java.util.stream.Collectors.toMap(
                                PerformanceTestSpecification.AuthenticationSpec::name,
                                java.util.function.Function.identity(),
                                (left, right) -> left,
                                LinkedHashMap::new));

        Map<String, RuntimeCredential> material = new LinkedHashMap<>();
        for (var workload : specification.spec().workloads()) {
            if (workload.authRef() == null || workload.authRef().isBlank()) {
                continue;
            }
            var auth = authByName.get(workload.authRef());
            if (auth == null || auth.secretRef() == null) {
                continue;
            }
            String ref = auth.secretRef().trim();
            if ("run://session".equalsIgnoreCase(ref)) {
                String value = supplied.get("session");
                if (value != null && !value.isBlank()) {
                    material.put(auth.name(), new RuntimeCredential(auth.name(), value));
                }
                continue;
            }
            if (ref.regionMatches(true, 0, "run://", 0, "run://".length())) {
                String key = ref.substring("run://".length());
                String value = supplied.get(key);
                if (value == null || value.isBlank()) {
                    throw new RuntimeCredentialMissingException(key);
                }
                material.put(auth.name(), new RuntimeCredential(auth.name(), value));
            } else if (ref.regionMatches(true, 0, "env://", 0, "env://".length())) {
                String key = ref.substring("env://".length());
                String value = System.getenv(key);
                if (value == null || value.isBlank()) {
                    throw new RuntimeCredentialMissingException(key);
                }
                material.put(auth.name(), new RuntimeCredential(auth.name(), value));
            }
        }
        return List.copyOf(material.values());
    }

    /**
     * Identifies what a trigger asked for, so a reused key can be told apart from a genuine retry.
     *
     * <p>Covers the version and the key. Two triggers agreeing on both are the same request however
     * many times they arrive; one that names a different version is a different request wearing the
     * same key.
     */
    private static String fingerprint(TriggerCommand command) {
        String canonical = "v1\n"
                + "definitionVersionId=" + command.definitionVersionId() + "\n"
                + "idempotencyKey=" + command.idempotencyKey() + "\n"
                + "credentialNames=" + command.credentials().keySet().stream().sorted().toList();
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available in this JVM", e);
        }
    }
}
