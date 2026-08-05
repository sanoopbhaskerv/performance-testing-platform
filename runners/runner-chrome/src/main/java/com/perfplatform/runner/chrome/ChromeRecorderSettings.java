package com.perfplatform.runner.chrome;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * How this machine runs the recording browser.
 *
 * @param binary          explicit Chrome path, or empty to search the usual locations
 * @param headless        false shows a real window, which is occasionally what a developer wants
 * @param startupTimeout  how long to wait for Chrome to publish its DevTools port
 * @param commandTimeout  how long any single DevTools command may take
 * @param frameQuality    JPEG quality 1-100 for screencast frames
 * @param maxCaptureBytes response bodies larger than this are recorded without their body
 */
public record ChromeRecorderSettings(
        Optional<Path> binary,
        boolean headless,
        Duration startupTimeout,
        Duration commandTimeout,
        int frameQuality,
        int maxCaptureBytes) {

    /**
     * Where Chrome usually lives, most specific first.
     *
     * <p>Chromium is included because a developer machine often has it instead of Chrome, and the
     * DevTools Protocol surface this adapter uses is identical.
     */
    private static final List<String> CANDIDATE_PATHS = List.of(
            "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
            "/Applications/Chromium.app/Contents/MacOS/Chromium",
            "/usr/bin/google-chrome",
            "/usr/bin/google-chrome-stable",
            "/usr/bin/chromium",
            "/usr/bin/chromium-browser",
            "/snap/bin/chromium");

    public ChromeRecorderSettings {
        Objects.requireNonNull(binary, "binary");
        Objects.requireNonNull(startupTimeout, "startupTimeout");
        Objects.requireNonNull(commandTimeout, "commandTimeout");
        if (frameQuality < 1 || frameQuality > 100) {
            throw new IllegalArgumentException("Chrome frame quality must be 1-100");
        }
        if (maxCaptureBytes < 0) {
            throw new IllegalArgumentException("Chrome capture limit must not be negative");
        }
    }

    public static ChromeRecorderSettings defaults() {
        return new ChromeRecorderSettings(Optional.empty(), true, Duration.ofSeconds(20),
                Duration.ofSeconds(10), 60, 256 * 1024);
    }

    /**
     * The browser this machine will actually run.
     *
     * @return the configured binary if it exists, otherwise the first candidate that does
     */
    public Optional<Path> resolveBinary() {
        if (binary.isPresent()) {
            return Files.isExecutable(binary.get()) ? binary : Optional.empty();
        }
        return CANDIDATE_PATHS.stream()
                .map(Path::of)
                .filter(Files::isExecutable)
                .findFirst();
    }
}
