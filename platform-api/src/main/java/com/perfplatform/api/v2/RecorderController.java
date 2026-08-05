package com.perfplatform.api.v2;

import com.perfplatform.api.v2.dto.RecorderAuditEventResponse;
import com.perfplatform.api.v2.dto.RecorderCaptureRequest;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
                        request.application(), request.environment()),
                callers.current()));

        return ResponseEntity.created(URI.create("/api/v2/recordings/" + response.id()))
                .body(response);
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
