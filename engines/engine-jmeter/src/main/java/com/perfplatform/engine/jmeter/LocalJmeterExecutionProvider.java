package com.perfplatform.engine.jmeter;

import com.perfplatform.domain.execution.DatasetDelivery;
import com.perfplatform.domain.execution.ExecutionPhase;
import com.perfplatform.domain.execution.ExecutionProvider;
import com.perfplatform.domain.execution.ProvisionRequest;
import com.perfplatform.domain.execution.SecretFileDelivery;
import com.perfplatform.domain.port.ArtifactStore;
import com.perfplatform.domain.port.ArtifactStore.ArtifactDescriptor;
import com.perfplatform.domain.port.ArtifactStore.ArtifactType;
import com.perfplatform.domain.port.ResultCatalog;
import com.perfplatform.domain.progress.ShardProgress;
import com.perfplatform.domain.progress.ShardProgressJson;
import com.perfplatform.domain.run.RunnerShard.ExecutionHandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Runs JMeter as a local process.
 *
 * <p><b>Development only.</b> CLAUDE.md §4 forbids the control-plane process from generating
 * material load, and §15 permits this adapter only under an explicit local profile. The composition
 * root enforces that; this class refuses nothing on its own, because a safety control that lives in
 * the thing being controlled is not a control.
 *
 * <p>It exists so the whole pipeline — compile, render, execute, collect, analyze, judge — can be
 * exercised against a real engine on one machine. Production execution runs in an isolated ephemeral
 * Kubernetes Job (Increment 1E) behind this same interface.
 *
 * <h2>How this differs from V1's ProcessBuilder use</h2>
 * V1 ran JMeter from inside the request path with no timeout, no cancellation and local paths
 * persisted as durable artifact references. Here:
 * <ul>
 *   <li>the command is an <b>argument list</b>, never a shell string, so nothing in a specification
 *       can be interpreted as a shell metacharacter;</li>
 *   <li>every process has a <b>deadline</b> from the plan's duration plus grace, and is destroyed
 *       when it passes;</li>
 *   <li>cancellation <b>destroys the process tree</b>, forcibly if it does not stop;</li>
 *   <li>outputs go through the {@link ArtifactStore}, so nothing persists a local path;</li>
 *   <li>stdout and stderr are captured and truncated, never streamed to the platform log.</li>
 * </ul>
 */
public final class LocalJmeterExecutionProvider implements ExecutionProvider {

    private static final Logger log = LoggerFactory.getLogger(LocalJmeterExecutionProvider.class);

    public static final String PROVIDER_ID = "local-jmeter";

    /**
     * The plugin jar every rendered plan depends on.
     *
     * <p>Matched by prefix rather than by exact name: the version moves, and pinning it here would
     * make a plugin upgrade look like a missing plugin.
     */
    private static final String CUSTOM_THREAD_GROUPS_JAR = "jmeter-plugins-casutg";

    /** Captured runner output is truncated: a failing run can emit megabytes of stack traces. */
    private static final int MAX_CAPTURED_OUTPUT = 64 * 1024;

    /**
     * How often a progress snapshot is published.
     *
     * <p>The same cadence the runner images use, so the live view behaves identically whichever
     * provider a deployment runs. Five seconds is short enough that a person watching sees the
     * effect of a stage change while it is happening, and long enough that reducing the results file
     * again is not the thing keeping a core busy.
     */
    private static final Duration PROGRESS_INTERVAL = Duration.ofSeconds(5);

    private final Clock clock;
    private final ArtifactStore artifacts;
    private final ResultCatalog catalog;

    /** The {@code jmeter} launcher. Resolved once so a missing install fails at startup, not mid-run. */
    private final Path jmeterCommand;

    /** Working directory for run scratch files. Pod-local and temporary — never a durable reference. */
    private final Path workDirectory;

    private final Map<String, LocalRun> running = new ConcurrentHashMap<>();

    /**
     * The last snapshot published per shard, so throughput can be measured over a window.
     *
     * <p>Separate from {@link LocalRun} because that record describes what was started and does not
     * change; this changes every five seconds and is the only mutable thing about a run here.
     */
    private final Map<String, JmeterProgressReducer.Window> progressWindows =
            new ConcurrentHashMap<>();

    public LocalJmeterExecutionProvider(Clock clock, ArtifactStore artifacts, ResultCatalog catalog,
            Path jmeterCommand, Path workDirectory) {
        this.clock = clock;
        this.artifacts = artifacts;
        this.catalog = catalog;
        this.jmeterCommand = jmeterCommand;
        this.workDirectory = workDirectory;
    }

    /**
     * Locates the JMeter launcher, or explains what to install.
     *
     * <p>Checked when the provider is built rather than when a run starts. A run that fails minutes
     * after being triggered because a binary was missing wastes a queue slot and reads like a
     * platform fault; failing at startup reads like the configuration problem it is.
     */
    public static Path locateJmeter(String configuredHome) {
        List<Path> candidates = new ArrayList<>();
        if (configuredHome != null && !configuredHome.isBlank()) {
            candidates.add(Path.of(configuredHome, "bin", "jmeter"));
            candidates.add(Path.of(configuredHome));
        }
        candidates.add(Path.of("/opt/jmeter/bin/jmeter"));
        candidates.add(Path.of("/opt/homebrew/bin/jmeter"));
        candidates.add(Path.of("/usr/local/bin/jmeter"));
        candidates.add(Path.of("/usr/bin/jmeter"));

        for (Path candidate : candidates) {
            if (Files.isExecutable(candidate)) {
                return candidate;
            }
        }

        throw new IllegalStateException("""
                JMeter was not found, so the local execution provider cannot run anything. \
                Install it (for example `brew install jmeter`, or download Apache JMeter 5.6.2 and \
                set JMETER_HOME), or set platform.orchestration.execution-provider=fake to exercise \
                the platform without generating load. Looked in: %s"""
                .formatted(candidates));
    }

    /**
     * Refuses to start unless JMeter has the Custom Thread Groups plugin.
     *
     * <p>Every plan this platform renders needs it: a closed model uses the Ultimate Thread Group so
     * concurrency glides through its profile, and an open model uses the Free-Form Arrivals Thread
     * Group, which has no vanilla equivalent at all. The production runner image installs it; a
     * developer's own JMeter usually has not.
     *
     * <p>Checked at startup, like the launcher itself, and for the same reason. JMeter does not fail
     * when it meets an element it cannot resolve — it logs and carries on with the rest of the plan,
     * so a missing plugin would surface as a run that completed successfully having generated no
     * load at all. That is the single worst failure this platform can have, and it is why this is a
     * refusal rather than a warning.
     *
     * @throws IllegalStateException naming the command that installs it
     */
    public static void requireCustomThreadGroups(Path jmeterCommand) {
        List<Path> searched = new ArrayList<>();
        for (Path extensions : extensionDirectories(jmeterCommand)) {
            searched.add(extensions);
            if (!Files.isDirectory(extensions)) {
                continue;
            }
            try (var entries = Files.list(extensions)) {
                if (entries.anyMatch(jar ->
                        jar.getFileName().toString().startsWith(CUSTOM_THREAD_GROUPS_JAR))) {
                    return;
                }
            } catch (IOException e) {
                // Unreadable rather than absent. Refusing here would stop a perfectly good
                // deployment over a directory listing, so this defers to the runtime instead.
                log.warn("Could not check {} for the Custom Thread Groups plugin", extensions, e);
                return;
            }
        }

        throw new IllegalStateException("""
                JMeter is missing the Custom Thread Groups plugin (jpgc-casutg), which every plan \
                this platform renders needs: staged load uses its Ultimate Thread Group so \
                concurrency glides rather than steps, and an arrival-rate model has no vanilla \
                equivalent at all. Without it JMeter would run the plan, skip the thread groups it \
                cannot resolve, and report a successful run that generated no load. \
                Install it with `PluginsManagerCMD.sh install jpgc-casutg`, or run \
                ./scripts/start-platform.sh, which installs it for you. Looked in: %s"""
                .formatted(searched));
    }

    /**
     * Where a JMeter installation might keep its extensions.
     *
     * <p>More than one candidate because the launcher is often not where the installation is. A
     * package manager typically puts a wrapper in {@code bin/} and the real tree elsewhere —
     * Homebrew's {@code /opt/homebrew/bin/jmeter} is a symlink into {@code Cellar/…/libexec} — so a
     * check that only looked beside the command would report a correctly installed plugin as
     * missing and refuse to start a working deployment.
     */
    private static List<Path> extensionDirectories(Path jmeterCommand) {
        Path resolved = jmeterCommand;
        try {
            resolved = jmeterCommand.toRealPath();
        } catch (IOException e) {
            // A command that cannot be resolved is still worth checking as written.
            log.debug("Could not resolve {} to a real path", jmeterCommand, e);
        }

        List<Path> candidates = new ArrayList<>();
        for (Path command : List.of(resolved, jmeterCommand)) {
            Path parent = command.getParent();
            Path home = parent == null ? null : parent.getParent();
            if (home == null) {
                continue;
            }
            candidates.add(home.resolve("lib").resolve("ext"));
            candidates.add(home.resolve("libexec").resolve("lib").resolve("ext"));
        }
        return candidates.stream().distinct().toList();
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
     * Yes. JMeter runs and traffic reaches the target.
     *
     * <p>True despite this being a development-only adapter: the distinction this answers is whether
     * the measurements mean anything, not whether the deployment is production-grade. Load generated
     * by a child process of the control plane is real load, and its numbers describe a real
     * interaction with a real system — subject to the caveat that the generator is sharing a JVM
     * host with the platform, which is why this adapter is refused in production.
     */
    @Override
    public boolean generatesRealLoad() {
        return true;
    }

    @Override
    public ProvisioningResult provision(ProvisionRequest request) {
        // Kept even though EngineRoutingExecutionProvider now dispatches by engine and a browser
        // shard should never arrive here. A misrouted shard would otherwise be handed to JMeter,
        // which loads what it is given, skips what it cannot resolve and exits zero — reporting a
        // successful run that generated no load. Refusing is the cheaper failure by a wide margin.
        if (!"jmeter".equalsIgnoreCase(request.engine())) {
            log.error("LocalJmeterExecutionProvider cannot run engine '{}' runId={} shardIndex={}",
                    request.engine(), request.runId(), request.shardIndex());
            return ProvisioningResult.rejected(
                    "Engine '" + request.engine() + "' cannot be run by the local-jmeter provider, "
                            + "which runs protocol workloads only. Browser workloads run on a "
                            + "Playwright provider: locally, configure "
                            + "platform.orchestration.playwright.workspace-root; in production, the "
                            + "Kubernetes provider with the playwright-runner image.");
        }

        Instant now = clock.instant();
        String uid = request.shardId().value().toString();

        try {
            Path directory = Files.createDirectories(
                    workDirectory.resolve(request.runId().value().toString()).resolve(uid));

            Path jmx = directory.resolve("workload.jmx");
            Files.write(jmx, workloadFor(request));

            // Beside the plan, because the process runs with this directory as its working
            // directory and the plan's CSVDataSet refers to a plain file name.
            writeDatasets(request, directory);
            writeSecretFiles(request, directory);

            Path results = directory.resolve("results.jtl");
            Path logFile = directory.resolve("jmeter.log");

            // An argument list, never a shell string. Nothing from a specification reaches a shell,
            // so a scenario name containing a semicolon is a name and not a second command.
            List<String> command = List.of(
                    jmeterCommand.toString(),
                    "-n",
                    "-t", jmx.toString(),
                    "-l", results.toString(),
                    "-j", logFile.toString(),
                    // Fail fast on a plan JMeter cannot load, rather than reporting an empty run.
                    "-Jjmeter.save.saveservice.output_format=csv",
                    "-Jjmeter.save.saveservice.print_field_names=true",
                    "-Jjmeter.save.saveservice.label=true",
                    "-Jjmeter.save.saveservice.successful=true",
                    "-Jjmeter.save.saveservice.time=true",
                    // Active threads per row, which is what the live view reports as users applying
                    // load. Taken from the file rather than from the plan's stage because the two
                    // differ exactly when it matters: a ramp behind schedule, or a generator at its
                    // own limit.
                    "-Jjmeter.save.saveservice.thread_counts=true",
                    "-Jjmeter.save.saveservice.timestamp_format=ms");

            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(directory.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(directory.resolve("runner-stdout.log").toFile());
            request.runtimeCredentials().forEach(credential ->
                    builder.environment().put(credential.environmentVariable(), credential.value()));
            Process process = builder.start();

            running.put(uid, new LocalRun(process, request, directory, now,
                    now.plus(request.deadline()), false));

            log.info("Local JMeter started runId={} shardIndex={} pid={} deadline={}",
                    request.runId(), request.shardIndex(), process.pid(), request.deadline());

            return ProvisioningResult.accepted(
                    new ExecutionHandle(PROVIDER_ID, "local-%s-%d".formatted(
                            uid.substring(0, 8), request.shardIndex()), uid));

        } catch (IOException | RuntimeException e) {
            // A failure to start is an outcome, not an exception the caller should handle: the run
            // needs to record it with a reason a consumer can act on.
            log.error("Local JMeter could not be started runId={} shardIndex={}",
                    request.runId(), request.shardIndex(), e);
            return ProvisioningResult.rejected(
                    "The local JMeter process could not be started. Check the platform log.");
        }
    }

    @Override
    public ExecutionStatus inspect(ExecutionHandle handle) {
        LocalRun run = running.get(handle.uid());
        if (run == null) {
            return ExecutionStatus.of(ExecutionPhase.NOT_FOUND);
        }

        Instant now = clock.instant();

        // The deadline is enforced here, by the thing holding the process. A workload that ignores
        // its stages must not keep generating load because the control plane was unreachable.
        if (run.process().isAlive() && now.isAfter(run.deadline())) {
            log.warn("Local JMeter passed its deadline runId={} shardIndex={}; destroying",
                    run.request().runId(), run.request().shardIndex());
            destroy(run.process());
            collectOutputs(run, now, 143);
            return ExecutionStatus.finished(ExecutionPhase.FAILED, 143, run.startedAt(), now);
        }

        if (run.process().isAlive()) {
            publishProgress(run, now);
            return new ExecutionStatus(ExecutionPhase.RUNNING, Optional.empty(),
                    Optional.of(run.startedAt()), Optional.empty());
        }

        int exitCode = run.process().exitValue();
        // One last snapshot before the outputs are collected, so a run that failed leaves an account
        // of what it was doing when it did rather than only a results file and an exit code.
        publishProgress(run, now);
        collectOutputs(run, now, exitCode);

        return ExecutionStatus.finished(
                exitCode == 0 && !run.cancelled() ? ExecutionPhase.SUCCEEDED : ExecutionPhase.FAILED,
                exitCode, run.startedAt(), now);
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
        log.info("Local JMeter cancelled runId={} shardIndex={} stopped={}",
                run.request().runId(), run.request().shardIndex(), stopped);

        return stopped
                ? CancellationResult.terminated("Process destroyed")
                : CancellationResult.pending("Destroy requested; process has not exited yet");
    }

    /**
     * Stops the process and everything it started.
     *
     * <p>JMeter's launcher is a shell script that execs a JVM, so destroying only the direct child
     * can leave the JVM generating load with nothing tracking it. Descendants are destroyed first,
     * then the parent, then forcibly if it has not gone.
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

    /**
     * Reduces the partial results file and publishes a snapshot, at most once per interval.
     *
     * <p>Published to the artifact store under this shard's prefix and <em>not</em> recorded in the
     * result catalog. A snapshot is overwritten in place every few seconds, so a recorded digest
     * would stop matching the object almost immediately — and the download path refuses content
     * whose digest does not match. The durable record of what a run was doing is the merged series
     * the control plane keeps, not this object.
     *
     * <p>Every failure here is swallowed. Publishing progress must not be able to affect what
     * {@code inspect} reports about a process: a run that failed because a chart could not be drawn
     * would be a strictly worse platform than one with no chart.
     */
    private void publishProgress(LocalRun run, Instant now) {
        String uid = run.request().shardId().value().toString();
        JmeterProgressReducer.Window previous = progressWindows.getOrDefault(uid,
                new JmeterProgressReducer.Window(run.startedAt(), 0));

        if (previous.capturedAt().plus(PROGRESS_INTERVAL).isAfter(now)) {
            return;
        }

        try {
            Path results = run.directory().resolve("results.jtl");
            if (!Files.isRegularFile(results)) {
                return;
            }

            String jtl = Files.readString(results, StandardCharsets.UTF_8);
            Optional<ShardProgress> snapshot = JmeterProgressReducer.reduce(
                    run.request(), jtl, now, run.startedAt(), previous);
            if (snapshot.isEmpty()) {
                return;
            }

            artifacts.store(
                    new ArtifactDescriptor(run.request().teamId(), run.request().runId(),
                            Optional.of(run.request().shardId()), ArtifactType.PROGRESS,
                            ShardProgress.OBJECT_NAME, "application/json"),
                    ShardProgressJson.write(snapshot.get()).getBytes(StandardCharsets.UTF_8));

            progressWindows.put(uid, new JmeterProgressReducer.Window(
                    now, snapshot.get().overall().sampleCount()));

        } catch (IOException | RuntimeException e) {
            log.debug("Could not publish a progress snapshot runId={} shardIndex={}",
                    run.request().runId(), run.request().shardIndex(), e);
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

        storeIfPresent(request, run.directory().resolve("results.jtl"),
                ArtifactType.RESULTS, "results.jtl", "text/csv");
        storeIfPresent(request, run.directory().resolve("jmeter.log"),
                ArtifactType.RUNNER_LOG, "jmeter.log", "text/plain");
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
     * Places this shard's dataset partitions where the plan expects to find them.
     *
     * <p>Read back from the artifact store by key rather than passed as content, so this adapter and
     * a Kubernetes runner do exactly the same thing: fetch by key, write into the workspace. The
     * control plane already sliced them, so nothing here decides which rows this shard sends.
     *
     * <p>A partition that cannot be read stops the shard from starting. Writing the plan and
     * omitting the data would produce a run that sends {@code ${orderId}} as literal text and
     * reports fast, healthy-looking latency for a target that answered 404 to everything.
     */
    private void writeDatasets(ProvisionRequest request, Path directory) throws IOException {
        for (DatasetDelivery delivery : request.datasets()) {
            byte[] content = artifacts.listForRun(request.runId()).stream()
                    .filter(reference -> reference.objectKey().equals(delivery.objectKey()))
                    .findFirst()
                    .flatMap(artifacts::read)
                    .orElseThrow(() -> new IllegalStateException(
                            "The dataset partition " + delivery.objectKey()
                                    + " is not in the artifact store, so this shard has no data "
                                    + "to send"));

            Files.write(directory.resolve(delivery.fileName()), content);
        }
    }

    /**
     * Places run-scoped secret files where the plan expects them.
     *
     * <p>Local execution is development-only, so this uses a scratch file rather than a Kubernetes
     * Secret volume. The lifecycle is still bounded by the process directory, and the content is not
     * stored as an artifact or written to logs.
     */
    private static void writeSecretFiles(ProvisionRequest request, Path directory)
            throws IOException {
        for (SecretFileDelivery delivery : request.secretFiles()) {
            Files.write(directory.resolve(delivery.fileName()), delivery.content());
        }
    }

    /** The rendered workload this shard executes, read back from the artifact store by key. */
    private byte[] workloadFor(ProvisionRequest request) {
        return artifacts.listForRun(request.runId()).stream()
                .filter(reference -> reference.objectKey().equals(request.workloadKey()))
                .findFirst()
                .flatMap(artifacts::read)
                .orElseThrow(() -> new IllegalStateException(
                        "The rendered workload " + request.workloadKey()
                                + " is not in the artifact store, so there is nothing to execute"));
    }
}
