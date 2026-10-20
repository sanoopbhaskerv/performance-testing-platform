package com.perfplatform.api.v2;

import com.perfplatform.api.v2.dto.ImportResponse;
import com.perfplatform.domain.importer.SourceImporter;
import com.perfplatform.domain.spec.SourceType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns something a consumer already has into draft scenarios.
 *
 * <p>The blank page is the real barrier to onboarding, not the YAML. Someone with a cURL command in
 * their clipboard is thirty seconds from a draft; someone staring at an empty specification is not.
 *
 * <h2>This endpoint creates nothing</h2>
 * It reads a document and returns a draft. No definition, no version, no run — so it is safe to call
 * repeatedly while iterating, and pasting the wrong thing costs nothing. Creating the definition is a
 * separate, deliberate act, which is also what keeps an import from becoming an executable test
 * without anyone having looked at it.
 *
 * <p>Findings are as much the answer as the scenarios. An import strips credentials and hosts, and
 * saying so is what lets a consumer replace them — rather than discovering at run time that
 * authentication is missing.
 */
@RestController
@RequestMapping("/api/v2/imports")
@Tag(name = "Imports (V2)", description = "Turning existing sources into draft scenarios")
public class SourceImportController {

    /** Raised when no importer handles the requested source type. */
    public static class UnsupportedSourceException extends RuntimeException {
        private final transient List<String> supported;

        public UnsupportedSourceException(String requested, List<String> supported) {
            super("No importer is available for source type '" + requested + "'. Available: "
                    + supported + ".");
            this.supported = List.copyOf(supported);
        }

        public List<String> supported() {
            return supported;
        }
    }

    private final Map<SourceType, SourceImporter> importers;

    /**
     * Every importer this deployment has.
     *
     * <p>Spring supplies them all; nothing here enumerates them, so adding an importer is adding a
     * bean. A source type with no importer is refused by name rather than silently returning nothing.
     */
    public SourceImportController(List<SourceImporter> available) {
        Map<SourceType, SourceImporter> byType = new LinkedHashMap<>();
        available.forEach(importer -> byType.put(importer.sourceType(), importer));
        this.importers = Map.copyOf(byType);
    }

    @Operation(
            summary = "Read a source into draft scenarios",
            description = "Creates nothing — it returns a draft plus findings, so it is safe to "
                    + "call repeatedly while iterating. Credentials and hosts are stripped and "
                    + "their removal reported: authentication belongs in spec.authentication as a "
                    + "secret reference, and where traffic goes is resolved from the team's "
                    + "registered targets.")
    @PostMapping(value = "/{sourceType}",
            consumes = {MediaType.TEXT_PLAIN_VALUE, MediaType.APPLICATION_JSON_VALUE, "application/yaml"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ImportResponse importSource(
            @Parameter(description = "One of: curl, openapi, har, postman", required = true)
            @PathVariable String sourceType,
            @RequestBody String document) {

        SourceType type = parse(sourceType);
        SourceImporter importer = importers.get(type);
        if (importer == null) {
            throw new UnsupportedSourceException(sourceType, supportedNames());
        }

        return ImportResponse.from(type.name().toLowerCase(Locale.ROOT),
                importer.importFrom(document));
    }

    /**
     * The requested type, or a refusal naming what is available.
     *
     * <p>An unparseable name and a name with no importer produce the same answer, because to a
     * consumer they are the same problem: this platform will not read that.
     */
    private SourceType parse(String requested) {
        try {
            return SourceType.from(requested);
        } catch (RuntimeException e) {
            throw new UnsupportedSourceException(requested, supportedNames());
        }
    }

    private List<String> supportedNames() {
        return importers.keySet().stream()
                .map(type -> type.name().toLowerCase(Locale.ROOT))
                .sorted()
                .toList();
    }
}
