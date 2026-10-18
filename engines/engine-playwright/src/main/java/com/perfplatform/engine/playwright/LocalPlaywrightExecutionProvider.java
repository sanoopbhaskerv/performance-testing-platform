package com.perfplatform.engine.playwright;

import com.perfplatform.domain.execution.ExecutionPhase;
import com.perfplatform.domain.execution.ExecutionProvider;
import com.perfplatform.domain.execution.ProvisionRequest;
import com.perfplatform.domain.execution.SecretFileDelivery;
import com.perfplatform.domain.port.ArtifactStore;
import com.perfplatform.domain.port.BrowserSourceStore;
import com.perfplatform.domain.port.ArtifactStore.ArtifactDescriptor;
import com.perfplatform.domain.port.ArtifactStore.ArtifactType;
import com.perfplatform.domain.port.ResultCatalog;
import com.perfplatform.domain.run.RunnerShard.ExecutionHandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Runs a reviewed Playwright journey as a local process.
 *
 * <p><b>Development only</b>, for the same reason {@link com.perfplatform.engine.jmeter} has a local
 * adapter: CLAUDE.md §4 forbids the control plane from generating material load, and §15 permits a
 * child-process adapter only under an explicit local profile. Real browsers are heavier than a
 * protocol generator, so the ceiling here is lower still — a cohort large enough to matter will
 * contend with the control plane for CPU long before a JMeter thread group would.
 *
 * <p>It exists so a browser journey can be executed <em>from the platform</em> rather than from a
 * terminal. That difference is the point: a run triggered here gets an immutable definition version,
 * an admission decision, a durable state machine, stored artifacts and objectives judged against
 * measurements — none of which {@code npx playwright test} produces.
 *
 * <h2>What this adapter does not do</h2>
 * It resolves {@code workspace://} sources only. A git-pinned source is fetched by the container
 * runner, which verifies that the commit the remote sent is the commit that was asked for; doing the
 * fetch here without that verification would make pinning look implemented while it was decorative.
 * A plan naming git coordinates is refused with an explanation rather than run from whatever happens
 * to be checked out.
 *
 * @see PlaywrightEngineProvider for how the plan this executes is compiled and rendered
 */
public final class LocalPlaywrightExecutionProvider implements ExecutionProvider {

    private static final Logger log =
            LoggerFactory.getLogger(LocalPlaywrightExecutionProvider.class);

    public static final String PROVIDER_ID = "local-playwright";

    /** Captured runner output is truncated: a failing cohort can emit megabytes of stack traces. */
    private static final int MAX_CAPTURED_OUTPUT = 64 * 1024;

    /** The bootstrap and reporter, copied out of this jar into each shard's working directory. */
    private static final List<String> RUNNER_SCRIPTS =
            List.of("perf-runner.mjs", "perf-reporter.mjs", "perf-fixture.mjs");

    private final BrowserSourceStore sources;
    private final Clock clock;
    private final ArtifactStore artifacts;
    private final ResultCatalog catalog;

    /** The {@code node} launcher. Resolved once so a missing install fails at startup, not mid-run. */
    private final Path nodeCommand;

    /** The {@code npx} launcher, which starts Playwright from the project's own install. */
    private final Path npxCommand;

    /** The checkout {@code workspace://} paths resolve against. Nothing outside it is readable. */
    private final Path workspaceRoot;

    /** Working directory for run scratch files. Pod-local and temporary — never a durable reference. */
    private final Path workDirectory;

    private final Map<String, LocalRun> running = new ConcurrentHashMap<>();

    public LocalPlaywrightExecutionProvider(Clock clock, ArtifactStore artifacts,
            ResultCatalog catalog, Path nodeCommand, Path npxCommand, Path workspaceRoot,
            Path workDirectory) {
        this(clock, artifacts, catalog, nodeCommand, npxCommand, workspaceRoot, workDirectory, null);
    }

    public LocalPlaywrightExecutionProvider(Clock clock, ArtifactStore artifacts,
            ResultCatalog catalog, Path nodeCommand, Path npxCommand, Path workspaceRoot,
            Path workDirectory, BrowserSourceStore sources) {
        this.sources = sources;
        this.clock = clock;
        this.artifacts = artifacts;
        this.catalog = catalog;
        this.nodeCommand = nodeCommand;
        this.npxCommand = npxCommand;
        this.workspaceRoot = workspaceRoot;
        this.workDirectory = workDirectory;
    }

    /**
     * Locates a Node command, or explains what to install.
     *
     * <p>Checked when the provider is built rather than when a run starts, for the reason the JMeter
     * adapter gives: a run that fails minutes after being triggered because a binary was missing has
     * already consumed a queue slot and reads like a platform fault rather than a setup problem.
     */
    public static Path locateNodeCommand(String configured, String name) {
        List<Path> candidates = new java.util.ArrayList<>();
        if (configured != null && !configured.isBlank()) {
            candidates.add(Path.of(configured));
        }
        candidates.add(Path.of("/opt/homebrew/bin/" + name));
        candidates.add(Path.of("/usr/local/bin/" + name));
        candidates.add(Path.of("/usr/bin/" + name));

        for (Path candidate : candidates) {
            if (Files.isExecutable(candidate)) {
                return candidate;
            }
        }

        throw new IllegalStateException("""
                `%s` was not found, so the local Playwright provider cannot run browser journeys. \
                Install Node.js (for example `brew install node`) or set \
                platform.orchestration.playwright.%s-command. Looked in: %s"""
                .formatted(name, name, candidates));
    }

    /**
     * Refuses to start unless the workspace root exists.
     *
     * <p>A missing root would present as every browser run failing to find its script — a per-run
     * error repeated forever, rather than the single configuration mistake it is.
     */
    public static Path requireWorkspaceRoot(String configured) {
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException(
                    "The local Playwright provider needs a workspace root: it is the checkout that "
                            + "workspace:// source paths resolve against, and nothing outside it is "
                            + "readable. Set platform.orchestration.playwright.workspace-root.");
        }
        Path root = Path.of(configured).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException(
                    "The configured Playwright workspace root " + root + " is not a directory, so "
                            + "no browser journey could be resolved from it.");
        }
        return root;
    }

    private record LocalRun(Process process, ProvisionRequest request, Path directory,
            Instant startedAt, Instant deadline, boolean cancelled) {

        LocalRun cancelledCopy() {
            return new LocalRun(process, request, directory, startedAt, deadline, true);
        }
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    /**
     * Yes. Real browsers load real pages against the target.
     *
     * <p>True for the same reason the local JMeter adapter says so: the question is whether the
     * measurements describe a real interaction, not whether the deployment is production-grade. The
     * caveat that the cohort shares a host with the control plane is why this adapter is refused in
     * production, not a reason to call its measurements synthetic.
     */
    @Override
    public boolean generatesRealLoad() {
        return true;
    }

    @Override
    public ProvisioningResult provision(ProvisionRequest request) {
        if (!PlaywrightEngineProvider.ENGINE_ID.equalsIgnoreCase(request.engine())) {
            return ProvisioningResult.rejected(
                    "Engine '" + request.engine() + "' cannot be run by the local-playwright "
                            + "provider, which starts browser journeys only.");
        }

        Instant now = clock.instant();
        String uid = request.shardId().value().toString();

        try {
            Path directory = Files.createDirectories(
                    workDirectory.resolve(request.runId().value().toString()).resolve(uid));

            Path plan = directory.resolve("playwright-plan.json");
            byte[] rendered = workloadFor(request);
            Files.write(plan, rendered);
            // Uploaded sources are fetched through the owning team's namespace and checked again
            // before Node is started. No source is evaluated inside the control plane.
            String document = new String(rendered, StandardCharsets.UTF_8);
            var match = java.util.regex.Pattern.compile("artifact://teams/[a-f0-9-]+/sources/playwright/([a-f0-9]{64})/journey\\.spec\\.ts").matcher(document);
            Path sourceFile = directory.resolve("journey.spec.ts");
            if (match.find()) {
                String digest = "sha256:" + match.group(1);
                BrowserSourceStore.requireOwned(request.teamId(), match.group(), digest);
                if (sources == null) throw new IllegalStateException("Browser source storage is unavailable");
                byte[] script = sources.read(request.teamId(), digest)
                        .orElseThrow(() -> new IllegalArgumentException("The uploaded browser source is missing"));
                if (!BrowserSourceStore.digest(script).equals(digest)) throw new IllegalArgumentException("Browser source digest mismatch");
                Files.write(sourceFile, script);
            }

            copyRunnerScripts(directory);
            writeSecretFiles(request, directory);

            Path results = directory.resolve("results.csv");

            // An argument list, never a shell string, so nothing in a specification — a scenario
            // name, a workspace path — can be read as a second command.
            List<String> command = List.of(
                    nodeCommand.toString(), directory.resolve("perf-runner.mjs").toString());

            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(directory.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(directory.resolve("runner-stdout.log").toFile());

            Map<String, String> env = builder.environment();
            env.put("PERF_PLAN_FILE", plan.toString());
            env.put("PERF_WORK_DIR", directory.toString());
            env.put("PERF_WORKSPACE_ROOT", workspaceRoot.toString());
            env.put("PERF_RESULTS_FILE", results.toString());
            env.put("PERF_NPX_COMMAND", npxCommand.toString());
            env.put("PERF_RUNTIME_ROOT", workspaceRoot.resolve("runner-images/playwright-runner").toString());
            env.put("PERF_TEAM_ID", request.teamId().toString());
            if (Files.isRegularFile(sourceFile)) env.put("PERF_SOURCE_FILE", sourceFile.toString());
            // Run-scoped credentials reach the browser through the environment and are never written
            // to the plan, an artifact or a log.
            request.runtimeCredentials().forEach(credential ->
                    env.put(credential.environmentVariable(), credential.value()));

            Process process = builder.start();

            running.put(uid, new LocalRun(process, request, directory, now,
                    now.plus(request.deadline()), false));

            log.info("Local Playwright started runId={} shardIndex={} pid={} deadline={}",
                    request.runId(), request.shardIndex(), process.pid(), request.deadline());

            return ProvisioningResult.accepted(
                    new ExecutionHandle(PROVIDER_ID, "local-pw-%s-%d".formatted(
                            uid.substring(0, 8), request.shardIndex()), uid));

        } catch (IOException | RuntimeException e) {
            log.error("Local Playwright could not be started runId={} shardIndex={}",
                    request.runId(), request.shardIndex(), e);
            return ProvisioningResult.rejected(
                    "The local Playwright runner could not be started. Check the platform log.");
        }
    }

    @Override
    public ExecutionStatus inspect(ExecutionHandle handle) {
        LocalRun run = running.get(handle.uid());
        if (run == null) {
            return ExecutionStatus.of(ExecutionPhase.NOT_FOUND);
        }

        Instant now = clock.instant();

        // Enforced here, by the thing holding the process: a cohort that hangs on a dialog must not
        // keep browsers open because the control plane was unreachable.
        if (run.process().isAlive() && now.isAfter(run.deadline())) {
            log.warn("Local Playwright passed its deadline runId={} shardIndex={}; destroying",
                    run.request().runId(), run.request().shardIndex());
            destroy(run.process());
            collectOutputs(run, now, 143);
            return ExecutionStatus.finished(ExecutionPhase.FAILED, 143, run.startedAt(), now);
        }

        if (run.process().isAlive()) {
            return new ExecutionStatus(ExecutionPhase.RUNNING, Optional.empty(),
                    Optional.of(run.startedAt()), Optional.empty());
        }

        int exitCode = run.process().exitValue();
        collectOutputs(run, now, exitCode);

        // A failing journey is a result, not a broken shard. Playwright exits non-zero when an
        // assertion fails, and treating that as a shard failure would discard the timings of every
        // journey that did complete — including the ones that show why the assertion failed.
        // The shard failed only if it produced nothing to read.
        boolean measured = hasSamples(run.directory().resolve("results.csv"));
        ExecutionPhase phase = run.cancelled() || !measured
                ? ExecutionPhase.FAILED
                : ExecutionPhase.SUCCEEDED;

        if (exitCode != 0 && measured) {
            log.info("Local Playwright exited {} but recorded journeys runId={} shardIndex={}; "
                            + "the objectives decide the verdict",
                    exitCode, run.request().runId(), run.request().shardIndex());
        }

        return ExecutionStatus.finished(phase, exitCode, run.startedAt(), now);
    }

    @Override
    public CancellationResult cancel(ExecutionHandle handle) {
        LocalRun run = running.get(handle.uid());
        if (run == null) {
            // Cancelling something already gone succeeds: cancellation is retried by design, and a
            // second attempt that failed would leave the run unable to reach a terminal state.
            return CancellationResult.terminated("No such process; already terminated");
        }

        running.put(handle.uid(), run.cancelledCopy());

        if (!run.process().isAlive()) {
            return CancellationResult.terminated("Process had already exited");
        }

        destroy(run.process());
        collectOutputs(run, clock.instant(), 143);

        boolean stopped = !run.process().isAlive();
        log.info("Local Playwright cancelled runId={} shardIndex={} stopped={}",
                run.request().runId(), run.request().shardIndex(), stopped);

        return stopped
                ? CancellationResult.terminated("Process destroyed")
                : CancellationResult.pending("Destroy requested; process has not exited yet");
    }

    /**
     * Stops the runner and every browser it started.
     *
     * <p>Descendants first, and forcibly if they linger. This matters more here than for a protocol
     * engine: the tree is node → npx → the Playwright test runner → one browser per session, and
     * destroying only the direct child leaves real browsers holding connections to the target with
     * nothing tracking them.
     */
    private void destroy(Process process) {
        process.descendants().forEach(ProcessHandle::destroy);
        process.destroy();

        try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    /** Whether the runner recorded at least one journey, rather than only the CSV header. */
    private static boolean hasSamples(Path results) {
        if (!Files.isRegularFile(results)) {
            return false;
        }
        try (var lines = Files.lines(results, StandardCharsets.UTF_8)) {
            return lines.skip(1).anyMatch(line -> !line.isBlank());
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Moves a finished run's files into the artifact store.
     *
     * <p>Idempotent: {@link #inspect} is called on every reconciliation pass and will re-observe a
     * finished process, so re-storing the same key must be a no-op rather than a duplicate.
     */
    private void collectOutputs(LocalRun run, Instant at, int exitCode) {
        ProvisionRequest request = run.request();

        storeIfPresent(request, run.directory().resolve("results.csv"),
                ArtifactType.RESULTS, "results.csv", "text/csv");
        storeIfPresent(request, run.directory().resolve("playwright-report.json"),
                ArtifactType.REPORT, "playwright-report.json", "application/json");
        storeIfPresent(request, run.directory().resolve("playwright-report.html"),
                ArtifactType.REPORT, "playwright-report.html", "text/html");
        Path evidence = run.directory().resolve("test-output");
        if (Files.isDirectory(evidence)) {
            try {
                new ProcessBuilder("tar", "-czf", run.directory().resolve("browser-evidence.tar.gz").toString(),
                        "-C", run.directory().toString(), "test-output").start().waitFor(15, TimeUnit.SECONDS);
                storeIfPresent(request, run.directory().resolve("browser-evidence.tar.gz"),
                        ArtifactType.REPORT, "browser-evidence.tar.gz", "application/gzip");
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            }
        }
        storeIfPresent(request, run.directory().resolve("runner-stdout.log"),
                ArtifactType.RUNNER_LOG, "runner-stdout.log", "text/plain");

        store(request, ArtifactType.RUNNER_MANIFEST, "runner-manifest.json", "application/json", """
                {"runId":"%s","shardId":"%s","shardIndex":%d,"shardCount":%d,"engine":"%s",\
                "planDigest":"%s","provider":"%s","startedAt":"%s","completedAt":"%s",\
                "exitCode":%d,"status":"%s"}"""
                .formatted(request.runId(), request.shardId(), request.shardIndex(),
                        request.shardCount(), request.engine(), request.planDigest().value(),
                        PROVIDER_ID, run.startedAt(), at, exitCode,
                        exitCode == 0 ? "COMPLETED" : "FAILED")
                .getBytes(StandardCharsets.UTF_8));
    }

    private void storeIfPresent(ProvisionRequest request, Path file, ArtifactType type,
            String fileName, String contentType) {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            byte[] content = Files.readAllBytes(file);
            if (type == ArtifactType.RUNNER_LOG && content.length > MAX_CAPTURED_OUTPUT) {
                content = truncated(content);
            }
            store(request, type, fileName, contentType, content);
        } catch (IOException e) {
            log.error("Could not read runner output {} for runId={}", fileName, request.runId(), e);
        }
    }

    private void store(ProvisionRequest request, ArtifactType type, String fileName,
            String contentType, byte[] content) {

        ArtifactStore.ArtifactReference stored = artifacts.store(
                new ArtifactDescriptor(request.teamId(), request.runId(),
                        Optional.of(request.shardId()), type, fileName, contentType),
                content);
        catalog.recordArtifact(stored);
    }

    private static byte[] truncated(byte[] content) {
        String notice = "\n... truncated; the runner produced more output than is retained.\n";
        byte[] head = new byte[MAX_CAPTURED_OUTPUT];
        System.arraycopy(content, 0, head, 0, MAX_CAPTURED_OUTPUT);
        return (new String(head, StandardCharsets.UTF_8) + notice).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Copies the bootstrap and reporter out of this jar into the shard's working directory.
     *
     * <p>Shipped as resources rather than as files on disk so the adapter works from a packaged jar,
     * and copied per shard rather than shared so two concurrent runs cannot observe each other's
     * scratch state.
     */
    private static void copyRunnerScripts(Path directory) throws IOException {
        for (String script : RUNNER_SCRIPTS) {
            try (InputStream in = LocalPlaywrightExecutionProvider.class
                    .getResourceAsStream("/playwright-runner/" + script)) {
                if (in == null) {
                    throw new IllegalStateException(
                            "The runner script " + script + " is missing from the engine-playwright "
                                    + "jar, so no browser journey can be started");
                }
                Files.write(directory.resolve(script), in.readAllBytes());
            }
        }
    }

    /**
     * Places run-scoped secret files where the journey expects them.
     *
     * <p>Local execution is development-only, so this uses a scratch file rather than a Kubernetes
     * Secret volume. The lifecycle is still bounded by the process directory, and the content is
     * never stored as an artifact or written to a log.
     */
    private static void writeSecretFiles(ProvisionRequest request, Path directory)
            throws IOException {
        for (SecretFileDelivery delivery : request.secretFiles()) {
            Files.write(directory.resolve(delivery.fileName()), delivery.content());
        }
    }

    /** The rendered plan this shard executes, read back from the artifact store by key. */
    private byte[] workloadFor(ProvisionRequest request) {
        return artifacts.listForRun(request.runId()).stream()
                .filter(reference -> reference.objectKey().equals(request.workloadKey()))
                .findFirst()
                .flatMap(artifacts::read)
                .orElseThrow(() -> new IllegalStateException(
                        "The rendered plan " + request.workloadKey()
                                + " is not in the artifact store, so there is nothing to execute"));
    }
}
