package com.perfplatform.runner.chrome;

import com.perfplatform.domain.port.RecorderBrowser.RecorderBrowserUnavailableException;
import com.perfplatform.domain.recorder.RecorderSessionPolicy;
import com.perfplatform.domain.recorder.RecorderViewport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A Chrome child process and the DevTools endpoint it published.
 *
 * <p>{@code ProcessBuilder} here is the same allowance CLAUDE.md §15 grants
 * {@code LocalJmeterExecutionProvider}: local and development profiles only, arguments passed as a
 * list and never as a shell string, output captured rather than inherited, and the child destroyed
 * on close. The production path for a recorder is a Pod, not this.
 */
final class ChromeProcess implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ChromeProcess.class);

    /** Chrome writes the chosen port here when started with {@code --remote-debugging-port=0}. */
    private static final String ACTIVE_PORT_FILE = "DevToolsActivePort";
    private static final Duration POLL_INTERVAL = Duration.ofMillis(50);
    private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(5);

    private final Process process;
    private final Path userDataDir;
    private final URI debuggerEndpoint;

    private ChromeProcess(Process process, Path userDataDir, URI debuggerEndpoint) {
        this.process = process;
        this.userDataDir = userDataDir;
        this.debuggerEndpoint = debuggerEndpoint;
    }

    static ChromeProcess launch(ChromeRecorderSettings settings, RecorderSessionPolicy policy,
            RecorderViewport viewport) {

        Path binary = settings.resolveBinary().orElseThrow(() ->
                new RecorderBrowserUnavailableException(
                        "No Chrome or Chromium binary was found on this machine."));

        Path userDataDir;
        try {
            userDataDir = Files.createTempDirectory("perf-recorder-");
        } catch (IOException e) {
            throw new RecorderBrowserUnavailableException(
                    "Could not create a workspace for the recording browser", e);
        }

        List<String> command = ChromeCommandLine.build(binary, userDataDir, policy, viewport,
                settings.headless(), 0);

        Process started;
        try {
            started = new ProcessBuilder(command)
                    // Discarded rather than inherited: Chrome's stderr carries the URLs it is
                    // loading, and those belong to the recorded application, not the platform log.
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException e) {
            deleteQuietly(userDataDir);
            throw new RecorderBrowserUnavailableException(
                    "Could not start the recording browser: " + e.getMessage(), e);
        }

        try {
            URI endpoint = awaitDebuggerEndpoint(started, userDataDir, settings.startupTimeout());
            log.info("Recorder browser started, pid={}", started.pid());
            return new ChromeProcess(started, userDataDir, endpoint);
        } catch (RuntimeException e) {
            started.destroyForcibly();
            deleteQuietly(userDataDir);
            throw e;
        }
    }

    /**
     * Waits for Chrome to publish its port, then builds the browser-level WebSocket URL.
     *
     * <p>The {@code DevToolsActivePort} file is used rather than parsing stderr: stderr is
     * discarded here, and the file is the documented contract for {@code --remote-debugging-port=0}.
     * Its first line is the port and its second is the browser WebSocket path.
     */
    private static URI awaitDebuggerEndpoint(Process process, Path userDataDir, Duration timeout) {
        Path portFile = userDataDir.resolve(ACTIVE_PORT_FILE);
        Instant deadline = Instant.now().plus(timeout);

        while (Instant.now().isBefore(deadline)) {
            if (!process.isAlive()) {
                throw new RecorderBrowserUnavailableException(
                        "The recording browser exited immediately with code " + process.exitValue()
                                + ".");
            }
            if (Files.isReadable(portFile)) {
                try {
                    List<String> lines = Files.readAllLines(portFile);
                    if (lines.size() >= 2 && !lines.get(0).isBlank()) {
                        return URI.create("ws://127.0.0.1:" + lines.get(0).trim()
                                + lines.get(1).trim());
                    }
                } catch (IOException e) {
                    // Chrome writes the file in two steps; a partial read simply retries.
                    log.trace("Recorder browser port file not complete yet");
                }
            }
            sleep(POLL_INTERVAL);
        }
        throw new RecorderBrowserUnavailableException(
                "The recording browser did not publish a DevTools port within " + timeout + ".");
    }

    URI debuggerEndpoint() {
        return debuggerEndpoint;
    }

    boolean isAlive() {
        return process.isAlive();
    }

    @Override
    public void close() {
        if (process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(SHUTDOWN_GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
        // The profile holds cookies and cached responses from whatever was recorded, so it is
        // removed rather than left in the temp directory (recorder security review §5).
        deleteQuietly(userDataDir);
    }

    private static void deleteQuietly(Path directory) {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.debug("Could not remove recorder workspace entry {}", path.getFileName());
                }
            });
        } catch (IOException | UncheckedIOException e) {
            log.debug("Could not remove recorder workspace {}", directory);
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RecorderBrowserUnavailableException(
                    "Interrupted while starting the recording browser");
        }
    }
}
