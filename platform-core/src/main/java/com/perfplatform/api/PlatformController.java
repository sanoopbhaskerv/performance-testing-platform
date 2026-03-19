package com.perfplatform.api;

import com.perfplatform.config.TeamConfig;
import com.perfplatform.config.TeamConfigLoader;
import com.perfplatform.engine.TestRunService;
import com.perfplatform.storage.TestRun;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Performance Testing Platform")
public class PlatformController {

    private final TestRunService testRunService;
    private final TeamConfigLoader configLoader;

    public PlatformController(TestRunService testRunService, TeamConfigLoader configLoader) {
        this.testRunService = testRunService;
        this.configLoader = configLoader;
    }

    // ── Test Triggers ────────────────────────────────────────────────────

    @Operation(summary = "Trigger a performance test for a team")
    @PostMapping("/tests/run")
    public ResponseEntity<TestRun> runTest(@RequestBody RunTestRequest request) {
        TestRun run = testRunService.createAndRun(request.team(), request.type());
        return ResponseEntity.accepted().body(run);
    }

    @Operation(summary = "Get status of a test run")
    @GetMapping("/tests/{runId}")
    public ResponseEntity<TestRun> getTestRun(@PathVariable String runId) {
        return testRunService.getById(runId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Get test run history for a team")
    @GetMapping("/tests/history")
    public List<TestRun> getHistory(@RequestParam String team) {
        return testRunService.getHistory(team);
    }

    @Operation(summary = "Download the unified HTML report for a test run")
    @GetMapping("/tests/{runId}/report")
    public ResponseEntity<Resource> downloadReport(@PathVariable String runId) {
        return testRunService.getById(runId)
                .filter(run -> run.getReportPath() != null)
                .map(run -> {
                    Resource res = new FileSystemResource(new File(run.getReportPath()));
                    return ResponseEntity.ok()
                            .contentType(MediaType.TEXT_HTML)
                            .header(HttpHeaders.CONTENT_DISPOSITION,
                                    "inline; filename=\"perf-report-" + runId + ".html\"")
                            .body(res);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // ── On-Demand Dumps ──────────────────────────────────────────────────

    @Operation(summary = "Trigger an on-demand heap dump for a team's target service")
    @PostMapping("/dump/heap")
    public ResponseEntity<Map<String, String>> triggerHeapDump(@RequestBody DumpRequest request) {
        TeamConfig config = configLoader.getConfig(request.team())
                .orElseThrow(() -> new IllegalArgumentException("No config found for team: " + request.team()));

        if (!config.getAnalysis().getHeapDump().isEnabled()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Heap dump analysis is not enabled in config for team: " + request.team() +
                            ". Set analysis.heapDump.enabled=true in your team config."));
        }
        return ResponseEntity.accepted().body(Map.of("message",
                "Heap dump requested for team: " + request.team() + ". Check the next test run report."));
    }

    @Operation(summary = "Trigger an on-demand thread dump for a team's target service")
    @PostMapping("/dump/thread")
    public ResponseEntity<Map<String, String>> triggerThreadDump(@RequestBody DumpRequest request) {
        TeamConfig config = configLoader.getConfig(request.team())
                .orElseThrow(() -> new IllegalArgumentException("No config found for team: " + request.team()));

        if (!config.getAnalysis().getThreadDump().isEnabled()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Thread dump analysis is not enabled in config for team: " + request.team() +
                            ". Set analysis.threadDump.enabled=true in your team config."));
        }
        return ResponseEntity.accepted().body(Map.of("message",
                "Thread dump requested for team: " + request.team() + ". Check the next test run report."));
    }

    // ── Config Management ────────────────────────────────────────────────

    @Operation(summary = "List all loaded team configs")
    @GetMapping("/config/teams")
    public List<String> listTeams() {
        return configLoader.getTeamNames();
    }

    @Operation(summary = "Hot-reload all team config files — no platform restart needed")
    @PostMapping("/config/reload")
    public ResponseEntity<Map<String, Object>> reloadConfigs() {
        int count = configLoader.reload();
        return ResponseEntity.ok(Map.of("teamsLoaded", count, "teams", configLoader.getTeamNames()));
    }

    // ── Request POJOs ────────────────────────────────────────────────────

    public record RunTestRequest(String team, String type) {
    }

    public record DumpRequest(String team, String targetName) {
    }
}
