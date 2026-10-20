package com.perfplatform.api.v2;

import com.perfplatform.api.v2.dto.ExecutionCapabilityResponse;
import com.perfplatform.domain.execution.ExecutionProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What this deployment is configured to do.
 *
 * <p>One endpoint, answering the question that determines whether anything else the API returns can
 * be believed: does triggering a run actually generate load? A deployment running a development
 * provider produces runs indistinguishable from real ones apart from the numbers being invented, and
 * a consumer cannot be expected to know that from the outside.
 *
 * <p>Authenticated like every other V2 endpoint but not team-scoped, because the answer is a property
 * of the deployment rather than of any team's data. It reveals no team, run, definition or target.
 *
 * <p>Holds the {@link ExecutionProvider} port directly, which is the one place in the API that does.
 * It only asks the provider to describe itself and never drives it — triggering, observing and
 * cancelling all go through orchestration use cases, as they must. Routing two self-describing
 * questions through a use case would add a layer that forwards them unchanged.
 */
@RestController
@RequestMapping("/api/v2/platform")
@Tag(name = "Platform (V2)", description = "What this deployment is configured to do")
public class PlatformCapabilityController {

    private final ExecutionProvider provider;

    public PlatformCapabilityController(ExecutionProvider provider) {
        this.provider = provider;
    }

    /**
     * The active execution provider.
     *
     * <p>Read from the provider itself rather than from configuration, so it reports what is wired
     * rather than what a property file says. The two can differ — a misspelled property fails startup
     * today, but a future provider chosen by profile or by fallback would not necessarily — and the
     * whole value of this endpoint is that it cannot be wrong.
     */
    @Operation(
            summary = "Get the active execution provider",
            description = "Reports whether this deployment generates real load. When "
                    + "generatesRealLoad is false, every run's measurements are invented and every "
                    + "objective is reported as INDETERMINATE.")
    @GetMapping(value = "/execution", produces = MediaType.APPLICATION_JSON_VALUE)
    public ExecutionCapabilityResponse execution() {
        return ExecutionCapabilityResponse.of(provider.providerId(), provider.generatesRealLoad());
    }
}
