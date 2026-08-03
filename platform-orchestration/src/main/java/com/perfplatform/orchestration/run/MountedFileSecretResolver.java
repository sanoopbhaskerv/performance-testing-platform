package com.perfplatform.orchestration.run;

import com.perfplatform.domain.port.SecretResolver;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Resolves {@code k8s://...} references from Kubernetes Secret files mounted into the control
 * plane Pod.
 *
 * <p>The Kubernetes API is not used for the value read. Operators mount the needed Secret keys
 * read-only, and configuration points at them by reference:
 *
 * <ul>
 *   <li>{@code k8s://secret-name/key} maps to {@code <mountRoot>/secret-name/key}</li>
 *   <li>{@code k8s://namespace/secret-name/key} maps to
 *       {@code <mountRoot>/namespace/secret-name/key}</li>
 * </ul>
 */
public final class MountedFileSecretResolver implements SecretResolver {

    private static final String SCHEME = "k8s://";

    private final Path mountRoot;

    public MountedFileSecretResolver(Path mountRoot) {
        this.mountRoot = mountRoot.toAbsolutePath().normalize();
    }

    @Override
    public SecretMaterial resolve(String reference) {
        if (reference == null || reference.isBlank()) {
            throw new SecretResolutionException("<blank>", "a non-empty reference is required");
        }
        String trimmed = reference.trim();
        if (!trimmed.regionMatches(true, 0, SCHEME, 0, SCHEME.length())) {
            throw new SecretResolutionException(trimmed,
                    "mounted-file resolver supports only k8s:// references");
        }

        Path file = pathFor(trimmed);
        try {
            String value = Files.readString(file, StandardCharsets.UTF_8).stripTrailing();
            if (value.isBlank()) {
                throw new SecretResolutionException(trimmed, "mounted file is empty");
            }
            return new SecretMaterial(trimmed, value);
        } catch (SecretResolutionException e) {
            throw e;
        } catch (IOException e) {
            throw new SecretResolutionException(trimmed, "mounted file is not readable", e);
        } catch (UncheckedIOException e) {
            throw new SecretResolutionException(trimmed, "mounted file is not readable", e);
        }
    }

    private Path pathFor(String reference) {
        String path = reference.substring(SCHEME.length());
        String[] segments = Arrays.stream(path.split("/"))
                .filter(segment -> !segment.isBlank())
                .toArray(String[]::new);
        if (segments.length != 2 && segments.length != 3) {
            throw new SecretResolutionException(reference,
                    "expected k8s://secret/key or k8s://namespace/secret/key");
        }

        Path resolved = mountRoot;
        for (String segment : segments) {
            if (".".equals(segment) || "..".equals(segment)) {
                throw new SecretResolutionException(reference, "path traversal is not permitted");
            }
            resolved = resolved.resolve(segment);
        }
        resolved = resolved.normalize();
        if (!resolved.startsWith(mountRoot)) {
            throw new SecretResolutionException(reference, "path traversal is not permitted");
        }
        return resolved;
    }
}
