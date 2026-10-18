package com.perfplatform.api.v2;

import com.perfplatform.api.v2.security.CallerContextProvider;
import com.perfplatform.domain.port.BrowserSourceStore;
import com.perfplatform.domain.port.TeamDirectory;
import com.perfplatform.domain.security.Scope;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/** Stores reviewed source only. Uploading never imports or executes user code in the API. */
@RestController
@RequestMapping("/api/v2/browser-sources")
public final class BrowserSourceController {
    private final BrowserSourceStore sources;
    private final TeamDirectory teams;
    private final CallerContextProvider callers;
    public BrowserSourceController(BrowserSourceStore sources, TeamDirectory teams, CallerContextProvider callers) {
        this.sources = sources; this.teams = teams; this.callers = callers;
    }

    public record UploadedSource(String uri, String digest, long sizeBytes) {}

    @Operation(summary = "Store a reviewed, self-contained Playwright script",
            description = "Accepts UTF-8 .spec.ts source, at most 1 MiB. Returns an immutable team-owned reference. Does not execute it.")
    @PostMapping(consumes = {"text/plain", "application/octet-stream"}, produces = "application/json")
    public ResponseEntity<UploadedSource> upload(@RequestParam String team,
            @RequestParam(defaultValue = "false") boolean reviewed, @RequestBody String script) {
        var teamId = teams.findByName(team).orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown team"));
        callers.current().requireAct(Scope.DEFINITIONS_WRITE, teamId);
        byte[] content = script.getBytes(StandardCharsets.UTF_8);
        if (!reviewed || script.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Review a Playwright script before uploading it.");
        if (content.length > 1024 * 1024) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Browser scripts are limited to 1 MiB.");
        String digest = BrowserSourceStore.digest(content);
        sources.put(teamId, digest, content);
        String uri = BrowserSourceStore.uri(teamId, digest);
        return ResponseEntity.created(URI.create(uri)).body(new UploadedSource(uri, digest, content.length));
    }
}
