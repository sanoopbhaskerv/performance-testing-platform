package com.perfplatform.engine.jmeter;

import com.perfplatform.domain.execution.DatasetDelivery;
import com.perfplatform.domain.execution.ExecutionPhase;
import com.perfplatform.domain.execution.ExecutionProvider;
import com.perfplatform.domain.execution.ProvisionRequest;
import com.perfplatform.domain.port.ArtifactStore;
import com.perfplatform.domain.port.ArtifactStore.ArtifactDescriptor;
import com.perfplatform.domain.port.ArtifactStore.ArtifactType;
import com.perfplatform.domain.port.ResultCatalog;
import com.perfplatform.domain.run.RunnerShard.ExecutionHandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
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

    /** Captured runner output is truncated: a failing run can emit megabytes of stack traces. */
    private static final int MAX_CAPTURED_OUTPUT = 64 * 1024;

    private final Clock clock;
    private final ArtifactStore artifacts;
    private final ResultCatalog catalog;

    /** The {@code jmeter} launcher. Resolved once so a missing install fails at startup, not mid-run. */
    private final Path jmeterCommand;

    /** Working directory for run scratch files. Pod-local and temporary — never a durable reference. */
    private final Path workDirectory;

    private final Map<String, LocalRun> running = new ConcurrentHashMap<>();

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
            return new ExecutionStatus(ExecutionPhase.RUNNING, Optional.empty(),
                    Optional.of(run.startedAt()), Optional.empty());
        }

        int exitCode = run.process().exitValue();
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
