package com.perfplatform.api.v2;

import com.perfplatform.api.v2.dto.RecorderAuditEventResponse;
import com.perfplatform.api.v2.dto.RecorderCaptureRequest;
import com.perfplatform.api.v2.dto.RecorderInputRequest;
import com.perfplatform.api.v2.dto.RecorderLiveUpdateResponse;
import com.perfplatform.api.v2.dto.RecordingSessionResponse;
import com.perfplatform.api.v2.dto.SanitizedRecorderCaptureResponse;
import com.perfplatform.api.v2.dto.StartRecordingRequest;
import com.perfplatform.api.v2.security.CallerContextProvider;
import com.perfplatform.domain.identity.RecorderSessionId;
import com.perfplatform.orchestration.recorder.ManageRecordingsUseCase;
import com.perfplatform.orchestration.recorder.ManageRecordingsUseCase.StartCommand;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/v2/recordings")
@Tag(name = "Recordings (V2)", description = "Governed browser-recording authoring sessions")
public class RecorderController {

    private final ManageRecordingsUseCase recordings;
    private final CallerContextProvider callers;

    public RecorderController(ManageRecordingsUseCase recordings, CallerContextProvider callers) {
        this.recordings = recordings;
        this.callers = callers;
    }

    @Operation(summary = "Create a governed recorder session")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RecordingSessionResponse> start(
            @Valid @RequestBody StartRecordingRequest request) {

        RecordingSessionResponse response = RecordingSessionResponse.from(recordings.start(
                new StartCommand(request.team(), request.teamId(),
                        request.application(), request.environment(),
                        request.viewportWidth(), request.viewportHeight()),
                callers.current()));

        return ResponseEntity.created(URI.create("/api/v2/recordings/" + response.id()))
                .body(response);
    }

    @Operation(summary = "Read one recorder session")
    @GetMapping(value = "/{sessionId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public RecordingSessionResponse get(@PathVariable String sessionId) {
        return RecordingSessionResponse.from(
                recordings.describe(RecorderSessionId.of(sessionId), callers.current()));
    }

    /**
     * Polls the live browser surface.
     *
     * <p>Polling rather than Server-Sent Events, for one blunt reason: {@code EventSource} cannot
     * set request headers, so an SSE stream would have to carry the caller's credential in the
     * query string. Frames are sequenced, so a poll is resumable and a slow client falls behind
     * without losing its place — and the console already polls for live run progress, so this is
     * the same shape as the rest of the UI rather than a second streaming mechanism.
     */
    @Operation(summary = "Poll the live recorder surface, blocked destinations and captures")
    @GetMapping(value = "/{sessionId}/live", produces = MediaType.APPLICATION_JSON_VALUE)
    public RecorderLiveUpdateResponse live(
            @PathVariable String sessionId,
            @RequestParam(defaultValue = "0") long afterFrame,
            @RequestParam(defaultValue = "0") long afterBlocked,
            @RequestParam(defaultValue = "0") long afterCapture,
            @RequestParam(defaultValue = "50") int limit) {

        return RecorderLiveUpdateResponse.from(recordings.live(RecorderSessionId.of(sessionId),
                afterFrame, afterBlocked, afterCapture, limit, callers.current()));
    }

    @Operation(summary = "Send one brokered interaction to the recorded browser")
    @PostMapping(value = "/{sessionId}/input", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void input(@PathVariable String sessionId,
            @Valid @RequestBody RecorderInputRequest request) {
        recordings.input(RecorderSessionId.of(sessionId), request.toDomain(), callers.current());
    }

    @Operation(summary = "Stop a recorder session and close its browser")
    @PostMapping(value = "/{sessionId}/stop", produces = MediaType.APPLICATION_JSON_VALUE)
    public RecordingSessionResponse stop(@PathVariable String sessionId) {
        return RecordingSessionResponse.from(
                recordings.stop(RecorderSessionId.of(sessionId), callers.current()));
    }

    @Operation(summary = "Sanitize one captured browser exchange")
    @PostMapping(value = "/{sessionId}/sanitize",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public SanitizedRecorderCaptureResponse sanitize(@PathVariable String sessionId,
            @RequestBody RecorderCaptureRequest request) {
        return SanitizedRecorderCaptureResponse.from(recordings.sanitize(
                RecorderSessionId.of(sessionId), request.toDomain(), callers.current()));
    }

    @Operation(summary = "Read recorder audit events")
    @GetMapping(value = "/{sessionId}/events", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<RecorderAuditEventResponse> events(
            @PathVariable String sessionId,
            @RequestParam(defaultValue = "0") long afterSequence,
            @RequestParam(defaultValue = "100") int limit) {

        return recordings.events(RecorderSessionId.of(sessionId), afterSequence, limit,
                        callers.current())
                .stream()
                .map(RecorderAuditEventResponse::from)
                .toList();
    }
}
