package com.perfplatform.api.v2;

import com.perfplatform.api.v2.dto.CancellationResponse;
import com.perfplatform.api.v2.dto.RunEventResponse;
import com.perfplatform.api.v2.dto.TestRunResponse;
import com.perfplatform.api.v2.dto.TriggerTestRunRequest;
import com.perfplatform.api.v2.security.CallerContextProvider;
import com.perfplatform.domain.identity.TestDefinitionVersionId;
import com.perfplatform.domain.identity.TestRunId;
import com.perfplatform.domain.security.CallerContext;
import com.perfplatform.orchestration.run.CancelTestRunUseCase;
import com.perfplatform.orchestration.run.ReadTestRunUseCase;
import com.perfplatform.orchestration.run.TriggerTestRunUseCase;
import com.perfplatform.orchestration.run.TriggerTestRunUseCase.TriggerCommand;
import com.perfplatform.orchestration.run.TriggerTestRunUseCase.TriggerResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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

    private final TriggerTestRunUseCase triggerRun;
    private final CancelTestRunUseCase cancelRun;
    private final ReadTestRunUseCase readRun;
    private final CallerContextProvider callers;

    public TestRunController(TriggerTestRunUseCase triggerRun,
            CancelTestRunUseCase cancelRun,
            ReadTestRunUseCase readRun,
            CallerContextProvider callers) {
        this.triggerRun = triggerRun;
        this.cancelRun = cancelRun;
        this.readRun = readRun;
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
                        idempotencyKey),
                caller);

        return ResponseEntity
                .accepted()
                .location(URI.create("/api/v2/test-runs/" + result.run().id()))
                .header(IDEMPOTENT_REPLAY, Boolean.toString(result.replayed()))
                .body(TestRunResponse.from(result.run()));
    }

    @Operation(summary = "Get a run's current state")
    @GetMapping(value = "/{runId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public TestRunResponse get(@PathVariable String runId) {
        return TestRunResponse.from(readRun.get(TestRunId.of(runId), callers.current()));
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
