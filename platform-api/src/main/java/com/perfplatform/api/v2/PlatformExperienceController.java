package com.perfplatform.api.v2;

import com.perfplatform.api.v2.security.CallerContextProvider;
import com.perfplatform.domain.port.TargetDirectory;
import com.perfplatform.domain.port.RecorderBrowser;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** Consumer-facing identity and available flows, rather than inferred deployment capabilities. */
@RestController
@RequestMapping("/api/v2/platform")
public final class PlatformExperienceController {
    private final Environment environment;
    private final CallerContextProvider callers;
    private final TargetDirectory teams;
    private final RecorderBrowser recorder;
    public PlatformExperienceController(Environment environment, CallerContextProvider callers,
            TargetDirectory teams, RecorderBrowser recorder) {
        this.environment = environment; this.callers = callers; this.teams = teams; this.recorder = recorder;
    }
    public record Experience(boolean legacyAvailable, boolean recorderAvailable) {}
    public record Caller(String subject, List<String> teams, boolean serviceIdentity) {}
    @GetMapping("/experience")
    public Experience experience() {
        return new Experience(!environment.acceptsProfiles(Profiles.of("production", "prod", "shared-runner")), recorder.isAvailable());
    }
    @GetMapping("/caller")
    public Caller caller() {
        var caller = callers.current();
        return new Caller(caller.subject(), caller.teamIds().stream().flatMap(id -> teams.teamName(id).stream()).sorted().toList(), caller.serviceIdentity());
    }
}
