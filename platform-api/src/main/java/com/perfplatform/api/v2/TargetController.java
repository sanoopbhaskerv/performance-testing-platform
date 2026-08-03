package com.perfplatform.api.v2;

import com.perfplatform.api.v2.dto.TargetRegistrationRequest;
import com.perfplatform.api.v2.dto.TargetResponse;
import com.perfplatform.api.v2.security.CallerContextProvider;
import com.perfplatform.orchestration.target.ManageTargetsUseCase;
import com.perfplatform.orchestration.target.ManageTargetsUseCase.RegisterCommand;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/v2/targets")
@Tag(name = "Targets (V2)", description = "Server-side targets that specifications may resolve to")
public class TargetController {

    private final ManageTargetsUseCase targets;
    private final CallerContextProvider callers;

    public TargetController(ManageTargetsUseCase targets, CallerContextProvider callers) {
        this.targets = targets;
        this.callers = callers;
    }

    @Operation(summary = "Register or replace a non-production target")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TargetResponse> register(
            @Valid @RequestBody TargetRegistrationRequest request) {

        TargetResponse response = TargetResponse.from(targets.register(new RegisterCommand(
                request.team(),
                request.application(),
                request.environment(),
                request.baseUrl(),
                request.targetClass()), callers.current()));

        return ResponseEntity.created(URI.create("/api/v2/targets"))
                .body(response);
    }

    @Operation(summary = "List targets visible to the caller")
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public List<TargetResponse> list() {
        return targets.listForCaller(callers.current()).stream()
                .map(TargetResponse::from)
                .toList();
    }
}
