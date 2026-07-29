package com.perfplatform.api.v2.error;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
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
