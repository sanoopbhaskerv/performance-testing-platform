package com.perfplatform.api.v2.error;

import com.perfplatform.api.v2.TestDefinitionController;
import com.perfplatform.api.v2.security.CallerContextProvider;
import com.perfplatform.domain.port.TestDefinitionRepository;
import com.perfplatform.domain.port.TestRunRepository;
import com.perfplatform.domain.run.InvalidRunTransitionException;
import com.perfplatform.domain.security.CallerContext;
import com.perfplatform.orchestration.definition.AuthorTestDefinitionUseCase;
import com.perfplatform.orchestration.run.CancelTestRunUseCase;
import com.perfplatform.orchestration.run.TriggerTestRunUseCase;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * RFC 7807 problem responses for the V2 API.
 *
 * <p>Scoped to {@code com.perfplatform.api.v2} so V1's error behaviour is untouched while both
 * surfaces coexist. Changing V1's responses would break the existing UI and Jenkins integrations.
 *
 * <p>Every problem carries a stable {@code code} and a correlation ID. The correlation ID is
 * logged with the underlying exception and returned to the caller, so a consumer can quote it in
 * a support request and an operator can find the cause — without the response ever carrying a
 * stack trace, a SQL error, a Kubernetes object or a secret-provider detail, all of which tell an
 * attacker about internals and can leak data into a consumer's CI logs.
 */
@RestControllerAdvice(basePackages = "com.perfplatform.api.v2")
public class V2ProblemHandler {

    private static final Logger log = LoggerFactory.getLogger(V2ProblemHandler.class);
    private static final String BASE_TYPE = "https://performance.ameriprise.com/problems/";

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadableBody(HttpMessageNotReadableException e,
            HttpServletRequest request) {
        // The parser message can quote request content, so it is logged and not returned.
        return problem(HttpStatus.BAD_REQUEST, "request.body.unreadable",
                "The request body could not be read.",
                request, e);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ProblemDetail handleUnsupportedMediaType(HttpMediaTypeNotSupportedException e,
            HttpServletRequest request) {
        return problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "request.contentType.unsupported",
                "Submit the specification as application/yaml or application/json.",
                request, e);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ProblemDetail handleMissingHeader(MissingRequestHeaderException e,
            HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "request.header.missing",
                "Required header '" + e.getHeaderName() + "' is missing.",
                request, e);
    }

    /**
     * Field-level validation failures.
     *
     * <p>Every field is reported, not the first. A caller fixing one field per round trip against an
     * API that queues load tests is a caller who gives up and hand-rolls the request.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleInvalidBody(MethodArgumentNotValidException e,
            HttpServletRequest request) {

        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "request.body.invalid",
                "The request body failed validation.", request, e);

        problem.setProperty("findings", e.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of(
                        "path", error.getField(),
                        "message", String.valueOf(error.getDefaultMessage())))
                .toList());
        return problem;
    }

    // ── Authentication and authorization ─────────────────────────────────

    @ExceptionHandler(CallerContextProvider.UnauthenticatedException.class)
    public ProblemDetail handleUnauthenticated(CallerContextProvider.UnauthenticatedException e,
            HttpServletRequest request) {
        return problem(HttpStatus.UNAUTHORIZED, "auth.unauthenticated",
                "This request carries no usable identity.",
                request, e);
    }

    /**
     * An authenticated caller who may not do this.
     *
     * <p>The response never says whether the failure was a missing scope or the wrong team, and
     * never names the resource's owner. Distinguishing them tells an unauthorized caller which
     * teams and runs exist, which is itself a disclosure.
     */
    @ExceptionHandler(CallerContext.AuthorizationException.class)
    public ProblemDetail handleUnauthorized(CallerContext.AuthorizationException e,
            HttpServletRequest request) {
        return problem(HttpStatus.FORBIDDEN, "auth.forbidden",
                "You are not permitted to perform this action.",
                request, e);
    }

    // ── Definitions ──────────────────────────────────────────────────────

    /**
     * A specification that did not validate.
     *
     * <p>422 with every finding, not 400 with the first. The request was well formed; the document
     * it carried is the problem, and a consumer fixing one field per round trip against an API that
     * queues load tests is a consumer who gives up and hand-rolls the request.
     */
    @ExceptionHandler(TestDefinitionController.InvalidSpecificationException.class)
    public ProblemDetail handleInvalidSpecification(
            TestDefinitionController.InvalidSpecificationException e, HttpServletRequest request) {

        ProblemDetail problem = problem(HttpStatus.UNPROCESSABLE_ENTITY, "specification.invalid",
                "The submitted specification did not validate and was not stored.", request, e);

        problem.setProperty("findings", e.findings().findings().stream()
                .map(finding -> {
                    Map<String, String> rendered = new LinkedHashMap<>();
                    rendered.put("code", finding.code());
                    rendered.put("severity", finding.severity().name());
                    rendered.put("path", finding.path());
                    rendered.put("message", finding.message());
                    if (finding.remediation() != null) {
                        rendered.put("remediation", finding.remediation());
                    }
                    return rendered;
                })
                .toList());
        return problem;
    }

    /**
     * A document naming a team or application the registry does not know.
     *
     * <p>The name is echoed because the caller supplied it; nothing is disclosed about what does
     * exist. Names resolve, they never create — a typo must not manufacture a team with no owner,
     * no quota and no approval policy.
     */
    @ExceptionHandler(AuthorTestDefinitionUseCase.UnknownOwnerException.class)
    public ProblemDetail handleUnknownOwner(AuthorTestDefinitionUseCase.UnknownOwnerException e,
            HttpServletRequest request) {

        ProblemDetail problem = problem(HttpStatus.UNPROCESSABLE_ENTITY,
                "specification." + e.field() + ".unknown", e.getMessage(), request, e);
        problem.setProperty("field", "metadata." + e.field());
        return problem;
    }

    @ExceptionHandler(AuthorTestDefinitionUseCase.UnknownDefinitionException.class)
    public ProblemDetail handleUnknownDefinition(
            AuthorTestDefinitionUseCase.UnknownDefinitionException e, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "definition.notFound",
                "No such test definition.", request, e);
    }

    @ExceptionHandler(TestDefinitionRepository.DuplicateDefinitionException.class)
    public ProblemDetail handleDuplicateDefinition(
            TestDefinitionRepository.DuplicateDefinitionException e, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "definition.duplicate", e.getMessage(), request, e);
    }

    // ── Runs ─────────────────────────────────────────────────────────────

    @ExceptionHandler(TriggerTestRunUseCase.UnknownDefinitionVersionException.class)
    public ProblemDetail handleUnknownVersion(
            TriggerTestRunUseCase.UnknownDefinitionVersionException e, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "definitionVersion.notFound",
                "No such test definition version.",
                request, e);
    }

    /**
     * A run identifier that names nothing the caller may see.
     *
     * <p>Deliberately indistinguishable from a run that does not exist. Returning 403 for someone
     * else's run and 404 for a fictional one turns the endpoint into an oracle for which run
     * identifiers are real.
     */
    @ExceptionHandler(CancelTestRunUseCase.UnknownRunException.class)
    public ProblemDetail handleUnknownRun(CancelTestRunUseCase.UnknownRunException e,
            HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "run.notFound",
                "No such test run.",
                request, e);
    }

    /**
     * A specification that did not pass validation.
     *
     * <p>422 rather than 400: the request was well formed and understood, and the problem is with
     * what it refers to. A caller can tell "I sent bad JSON" from "the test itself is not runnable".
     */
    @ExceptionHandler(TriggerTestRunUseCase.VersionNotExecutableException.class)
    public ProblemDetail handleNotExecutable(TriggerTestRunUseCase.VersionNotExecutableException e,
            HttpServletRequest request) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "definitionVersion.notExecutable",
                "This definition version did not pass validation and cannot be executed.",
                request, e);
    }

    /**
     * An idempotency key reused for a different request.
     *
     * <p>The detail names the run the key already belongs to, which is safe — the caller supplied
     * the key and owns the run — and is the one fact needed to resolve the situation.
     */
    @ExceptionHandler(TestRunRepository.IdempotencyConflictException.class)
    public ProblemDetail handleIdempotencyConflict(TestRunRepository.IdempotencyConflictException e,
            HttpServletRequest request) {

        ProblemDetail problem = problem(HttpStatus.CONFLICT, "run.idempotencyKey.conflict",
                "This idempotency key was already used for a different request. "
                        + "Use a new key, or resubmit the original request unchanged.",
                request, e);
        problem.setProperty("existingRunId", e.existingRunId().toString());
        return problem;
    }

    @ExceptionHandler(CancelTestRunUseCase.RunNotCancellableException.class)
    public ProblemDetail handleNotCancellable(CancelTestRunUseCase.RunNotCancellableException e,
            HttpServletRequest request) {

        ProblemDetail problem = problem(HttpStatus.CONFLICT, "run.notCancellable",
                e.getMessage(), request, e);
        problem.setProperty("state", e.state().name());
        return problem;
    }

    /**
     * A concurrent modification, or a transition the state machine forbids.
     *
     * <p>Both are 409 because both mean the same thing to a caller: the run is not where you thought
     * it was. Re-read it and decide again.
     */
    @ExceptionHandler({
            TestRunRepository.OptimisticLockException.class,
            InvalidRunTransitionException.class})
    public ProblemDetail handleConflict(RuntimeException e, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "run.conflict",
                "The run changed while this request was being processed. Re-read it and retry.",
                request, e);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException e,
            HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "request.invalid",
                "The request could not be processed.",
                request, e);
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception e, HttpServletRequest request) {
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal.error",
                "The request could not be completed. Quote the correlation id when reporting this.",
                request, e);
    }

    private ProblemDetail problem(HttpStatus status, String code, String detail,
            HttpServletRequest request, Exception cause) {
        String correlationId = UUID.randomUUID().toString();

        // The only place the cause is recorded. Correlation id ties it to the response.
        if (status.is5xxServerError()) {
            log.error("V2 request failed correlationId={} code={} path={}",
                    correlationId, code, request.getRequestURI(), cause);
        } else {
            log.warn("V2 request rejected correlationId={} code={} path={} reason={}",
                    correlationId, code, request.getRequestURI(), cause.getClass().getSimpleName());
        }

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(BASE_TYPE + code));
        problem.setTitle(status.getReasonPhrase());
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code);
        problem.setProperty("correlationId", correlationId);
        return problem;
    }
}
