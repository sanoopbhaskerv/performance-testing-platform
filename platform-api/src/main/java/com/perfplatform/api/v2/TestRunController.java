package com.perfplatform.api.v2;

import com.perfplatform.api.v2.dto.ArtifactResponse;
import com.perfplatform.api.v2.dto.CancellationResponse;
import com.perfplatform.api.v2.dto.ObjectiveOutcomeResponse;
import com.perfplatform.api.v2.dto.RunEventResponse;
import com.perfplatform.api.v2.dto.RunProgressResponse;
import com.perfplatform.api.v2.dto.RunReportResponse;
import com.perfplatform.api.v2.dto.TestRunResponse;
import com.perfplatform.api.v2.dto.TriggerTestRunRequest;
import com.perfplatform.api.v2.security.CallerContextProvider;
import com.perfplatform.domain.identity.ArtifactId;
import com.perfplatform.domain.identity.TestDefinitionVersionId;
import com.perfplatform.domain.identity.TestRunId;
import com.perfplatform.domain.security.CallerContext;
import com.perfplatform.orchestration.run.CancelTestRunUseCase;
import com.perfplatform.orchestration.run.ReadRunProgressUseCase;
import com.perfplatform.orchestration.run.ReadRunReportUseCase;
import com.perfplatform.orchestration.run.ReadRunReportUseCase.ArtifactContent;
import com.perfplatform.orchestration.run.ReadTestRunUseCase;
import com.perfplatform.orchestration.run.TriggerTestRunUseCase;
import com.perfplatform.orchestration.run.TriggerTestRunUseCase.TriggerCommand;
import com.perfplatform.orchestration.run.TriggerTestRunUseCase.TriggerResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/**
 * V2 run endpoints.
 *
 * <p>The trigger returns as soon as the request is durable. A performance test runs for minutes to
 * hours, so an endpoint that waited for a result would hold a connection open for the duration and
 * fail on the first proxy timeout — the caller polls or subscribes instead.
 */
@RestController
@RequestMapping("/api/v2/test-runs")
@Tag(name = "Test Runs (V2)", description = "Triggering, observing and cancelling performance runs")
public class TestRunController {

    /**
     * The idempotency key header.
     *
     * <p>A header rather than a body field, following the convention consumers already know from
     * payment APIs, and so that it applies to the request rather than describing the test.
     */
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    /** Set when a trigger returned an existing run instead of creating one. */
    private static final String IDEMPOTENT_REPLAY = "Idempotent-Replay";

    /** Carries a downloaded artifact's recorded digest, so a caller can repeat the verification. */
    private static final String ARTIFACT_DIGEST = "X-Artifact-Digest";

    /**
     * Stops a browser from re-deciding an artifact's type by sniffing its content.
     *
     * <p>Named here rather than taken from {@code HttpHeaders}, which has no constant for it.
     */
    private static final String CONTENT_TYPE_OPTIONS = "X-Content-Type-Options";

    private final TriggerTestRunUseCase triggerRun;
    private final CancelTestRunUseCase cancelRun;
    private final ReadTestRunUseCase readRun;
    private final ReadRunReportUseCase readReport;
    private final ReadRunProgressUseCase readProgress;
    private final CallerContextProvider callers;

    public TestRunController(TriggerTestRunUseCase triggerRun,
            CancelTestRunUseCase cancelRun,
            ReadTestRunUseCase readRun,
            ReadRunReportUseCase readReport,
            ReadRunProgressUseCase readProgress,
            CallerContextProvider callers) {
        this.triggerRun = triggerRun;
        this.cancelRun = cancelRun;
        this.readRun = readRun;
        this.readReport = readReport;
        this.readProgress = readProgress;
        this.callers = callers;
    }

    /**
     * Requests a run.
     *
     * <p>Returns {@code 202 Accepted} with a {@code Location} header. Not {@code 201}: the run
     * exists but nothing has executed, and reporting it as created would suggest the test had
     * started. The key makes retries safe — a retried trigger returns the original run rather than
     * starting a second load test against a real system.
     */
    @Operation(
            summary = "Trigger a performance test run",
            description = "Queues a run of an immutable definition version and returns immediately. "
                    + "Requires an Idempotency-Key header: repeating a request with the same key "
                    + "returns the original run rather than generating load twice.")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TestRunResponse> trigger(
            @Parameter(description = "Caller-supplied key, unique within the team", required = true)
            @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
            @Valid @RequestBody TriggerTestRunRequest request) {

        CallerContext caller = callers.current();
        TriggerResult result = triggerRun.trigger(
                new TriggerCommand(
                        TestDefinitionVersionId.of(request.definitionVersionId()),
                        idempotencyKey,
                        request.credentials()),
                caller);

        return ResponseEntity
                .accepted()
                .location(URI.create("/api/v2/test-runs/" + result.run().id()))
                .header(IDEMPOTENT_REPLAY, Boolean.toString(result.replayed()))
                .body(TestRunResponse.from(result.run()));
    }

    /**
     * The caller's teams' runs, newest first.
     *
     * <p>No team parameter. The caller's teams are known from their identity, and accepting one would
     * invite a request for a team they do not belong to — a question the API should not offer to
     * answer at all rather than answer with a refusal.
     */
    @Operation(
            summary = "List recent runs",
            description = "Runs belonging to the caller's teams, newest first.")
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public List<TestRunResponse> list(@RequestParam(defaultValue = "25") int limit) {
        return readRun.listForCaller(callers.current(), limit).stream()
                .map(TestRunResponse::from)
                .toList();
    }

    @Operation(
            summary = "Get a run's objective verdicts",
            description = "Empty until the run has been analyzed. INDETERMINATE means the objective "
                    + "could not be judged — incomplete results, or nothing measured in its scope.")
    @GetMapping(value = "/{runId}/objectives", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<ObjectiveOutcomeResponse> objectives(@PathVariable String runId) {
        return readRun.objectives(TestRunId.of(runId), callers.current()).stream()
                .map(ObjectiveOutcomeResponse::from)
                .toList();
    }

    @Operation(
            summary = "List a run's artifacts",
            description = "References only. Downloading is a separate authorized request.")
    @GetMapping(value = "/{runId}/artifacts", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<ArtifactResponse> artifacts(@PathVariable String runId) {
        return readRun.artifacts(TestRunId.of(runId), callers.current()).stream()
                .map(ArtifactResponse::from)
                .toList();
    }

    /**
     * One artifact's content.
     *
     * <p>Authorized on every request, and the grant is the request — no URL is ever minted or stored
     * (CLAUDE.md §17). A stored pre-signed URL is a reference that keeps working after the access that
     * created it was revoked, and a reference that works until it silently does not.
     *
     * <p>The digest is verified before anything is written to the response, so the platform never
     * serves as a run's evidence a file it can prove is not the file it measured.
     */
    @Operation(
            summary = "Download one artifact's content",
            description = "Authorized per request; no pre-signed URL is issued or stored. The content "
                    + "is verified against the digest recorded when it was stored, and refused if it "
                    + "does not match. Returns 404 for an artifact this caller may not read, which is "
                    + "the same answer as one that does not exist.")
    @GetMapping("/{runId}/artifacts/{artifactId}/content")
    public ResponseEntity<byte[]> artifactContent(
            @PathVariable String runId,
            @PathVariable String artifactId) {

        ArtifactContent content = readReport.artifactContent(
                TestRunId.of(runId), ArtifactId.of(artifactId), callers.current());

        byte[] body = content.content();

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, content.reference().contentType())
                // "attachment" rather than inline: these are runner outputs, and a results file or a
                // log rendered in the browser by content sniffing is a way to get a run's own artifact
                // executed in the platform's origin.
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\""
                        + ReadRunReportUseCase.downloadFileName(content.reference()) + "\"")
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(body.length))
                // The digest travels with the bytes, so a caller can repeat the verification the
                // platform just performed rather than taking it on trust.
                .header(ARTIFACT_DIGEST, content.reference().digest())
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header(CONTENT_TYPE_OPTIONS, "nosniff")
                .body(body);
    }

    /**
     * A run's report.
     *
     * <p>JSON rather than a rendered page. The console renders it, a pipeline can assert on it, and a
     * later increment can compare two of them — none of which works with server-rendered markup, which
     * is what V1 produced.
     */
    @Operation(
            summary = "Get a run's report",
            description = "Verdicts, measurements, workload profile, history and evidence, assembled "
                    + "from durable state. Measurements are null until the run has been analyzed, "
                    + "never zero. When synthetic is true, no load was generated and every "
                    + "measurement is invented.")
    @GetMapping(value = "/{runId}/report", produces = MediaType.APPLICATION_JSON_VALUE)
    public RunReportResponse report(@PathVariable String runId) {
        return RunReportResponse.from(
                readReport.report(TestRunId.of(runId), callers.current()));
    }

    @Operation(summary = "Get a run's current state")
    @GetMapping(value = "/{runId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public TestRunResponse get(@PathVariable String runId) {
        return TestRunResponse.from(readRun.get(TestRunId.of(runId), callers.current()));
    }

    /**
     * What a run is doing right now.
     *
     * <p>{@code 204} rather than an empty body full of zeros when nothing has been published yet.
     * "Not measured yet" and "measured nothing" are different facts, and a response of zeros would
     * let a client render the second while the first is true — which, for a live view, is the one
     * wrong answer that matters.
     *
     * <p>Polled. Server-Sent Events would suit this better and is a later increment; the merged
     * snapshots are durable either way, so switching the transport changes nothing about what a
     * client can ask for or resume from.
     */
    @Operation(
            summary = "Get a run's live measurements",
            description = "Throughput, latency, errors, per-operation breakdown, each generator's "
                    + "own last snapshot, and rule-based observations with their evidence. "
                    + "Everything is provisional: percentiles are bucket upper bounds, counts come "
                    + "from partial runner output, and provisionalObjectives are not the run's "
                    + "verdict — that is computed from the complete results once every shard has "
                    + "finished. Returns 204 when no generator has published anything yet.")
    @GetMapping(value = "/{runId}/progress", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RunProgressResponse> progress(
            @PathVariable String runId,
            @RequestParam(defaultValue = "120") int limit) {

        return readProgress.read(TestRunId.of(runId), limit, callers.current())
                .map(view -> ResponseEntity.ok()
                        // A live view is current for as long as the runners' publishing interval,
                        // and a cached one shown as current is exactly the failure this endpoint
                        // exists to prevent.
                        .header(HttpHeaders.CACHE_CONTROL, "no-store")
                        .body(RunProgressResponse.from(view)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * A run's history.
     *
     * <p>{@code afterSequence} lets a client that lost its connection resume from the last event it
     * saw rather than re-reading everything — the same cursor Server-Sent Events will use for
     * resumption in a later increment.
     */
    @Operation(
            summary = "Get a run's event history",
            description = "Append-only history in order. Pass afterSequence to resume from a "
                    + "previously seen event rather than re-reading the whole history.")
    @GetMapping(value = "/{runId}/events", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<RunEventResponse> events(
            @PathVariable String runId,
            @RequestParam(defaultValue = "0") long afterSequence,
            @RequestParam(defaultValue = "200") int limit) {

        return readRun.events(TestRunId.of(runId), afterSequence, limit, callers.current())
                .stream()
                .map(RunEventResponse::from)
                .toList();
    }

    /**
     * Asks a run to stop.
     *
     * <p>{@code 200} whether the run stopped immediately or a runner is still being terminated; the
     * response body distinguishes them. Repeating the request on an already-cancelled run also
     * returns {@code 200} — people retry a stop button that looks like it did nothing, and failing
     * the retry pushes them toward blunter instruments.
     */
    @Operation(
            summary = "Cancel a run",
            description = "Idempotent. Returns CANCELLED when the run stopped immediately, "
                    + "CANCELLATION_REQUESTED while a runner is being terminated, and "
                    + "ALREADY_CANCELLED for a repeat request.")
    @PostMapping(value = "/{runId}/cancel", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CancellationResponse> cancel(
            @PathVariable String runId,
            @RequestParam(required = false) String reason) {

        return ResponseEntity.status(HttpStatus.OK)
                .body(CancellationResponse.from(
                        cancelRun.cancel(TestRunId.of(runId), reason, callers.current())));
    }
}
