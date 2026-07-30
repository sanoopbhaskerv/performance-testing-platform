package com.perfplatform.api.v2.dto;

import com.perfplatform.domain.run.TestRun;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * A run as consumers see it.
 *
 * <p>A DTO rather than the storage entity, and rather than the domain aggregate. The entity would
 * make the database schema the public contract — V1's defect, where adding a column changes the API.
 * The domain aggregate is closer but still wrong to expose: it carries the reconciler's lease and
 * optimistic-lock version, which are internal mechanics that would become things consumers depend on.
 *
 * @param id                  run identifier, also the correlation value injected into workload traffic
 * @param teamId              owning team
 * @param definitionVersionId the immutable version being executed
 * @param executionPlanId     the compiled plan, null until the run is admitted
 * @param status              current durable state
 * @param environment         the environment being tested
 * @param requestedBy         who asked for the run
 * @param requestedAt         when it was asked for
 * @param queuedAt            when it entered the queue
 * @param startedAt           when load generation began
 * @param completedAt         when it reached a terminal state
 * @param cancellationRequested whether someone has asked for it to stop; true before the run has
 *                            actually reached {@code CANCELLED}, which is the interval a consumer
 *                            most wants to see rather than a stop button that appears to do nothing
 * @param failureCode         stable failure code, null unless the run failed
 * @param failureReason       sanitized explanation, null unless the run failed
 * @param synthetic           true when no load was generated and every measurement is invented.
 *                            Present on the run itself, not only in an artifact, so no consumer of
 *                            this API can display a number from this run without having been told
 */
@Schema(description = "State of a performance test run")
public record TestRunResponse(
        String id,
        String teamId,
        String definitionVersionId,
        String executionPlanId,
        String status,
        String environment,
        String requestedBy,
        Instant requestedAt,
        Instant queuedAt,
        Instant startedAt,
        Instant completedAt,
        boolean cancellationRequested,
        String failureCode,
        String failureReason,
        @Schema(description = "True when no load was generated, making every measurement invented. "
                + "Such a run reports INDETERMINATE objectives and can never pass.")
        boolean synthetic) {

    public static TestRunResponse from(TestRun run) {
        return new TestRunResponse(
                run.id().toString(),
                run.teamId().toString(),
                run.definitionVersionId().toString(),
                run.executionPlanId().map(Object::toString).orElse(null),
                run.state().name(),
                run.environment(),
                run.trigger().requestedBy(),
                run.timeline().requestedAt(),
                run.timeline().queuedAt().orElse(null),
                run.timeline().startedAt().orElse(null),
                run.timeline().completedAt().orElse(null),
                run.isCancellationRequested(),
                run.failure().map(failure -> failure.code().name()).orElse(null),
                run.failure().map(TestRun.Failure::sanitizedReason).orElse(null),
                run.synthetic());
    }
}
