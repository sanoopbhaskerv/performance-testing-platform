package com.perfplatform.demo;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.stream.Collectors;

@RestControllerAdvice
public class StudentApiExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiErrorResponse> handleStatusException(ResponseStatusException exception, HttpServletRequest request) {
        HttpStatus status = HttpStatus.valueOf(exception.getStatusCode().value());
        String message = exception.getReason() != null ? exception.getReason() : status.getReasonPhrase();
        return ResponseEntity.status(status).body(toResponse(status, message, request.getRequestURI()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        String message = exception.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(this::toFieldMessage)
                .collect(Collectors.joining(", "));
        HttpStatus status = HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(toResponse(status, message, request.getRequestURI()));
    }

    /**
     * The catch-all.
     *
     * <p>It must not swallow exceptions that already know their own status. Spring raises
     * {@code NoResourceFoundException} for an unmapped path, {@code HttpRequestMethodNotSupportedException}
     * for a wrong verb and several others, and every one of them implements {@link ErrorResponse}.
     * Reporting those as 500 makes an ordinary 404 look like the service is broken — and for a
     * service whose whole job is to be load-tested against, that is worse than cosmetic: a recorded
     * journey drops failed exchanges, so a 500 on {@code /} means the recording of a healthy service
     * quietly produces nothing.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception exception, HttpServletRequest request) {
        if (exception instanceof ErrorResponse errorResponse) {
            HttpStatus status = HttpStatus.valueOf(errorResponse.getStatusCode().value());
            return ResponseEntity.status(status)
                    .body(toResponse(status, status.getReasonPhrase(), request.getRequestURI()));
        }
        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        return ResponseEntity.status(status)
                .body(toResponse(status, "Unexpected error while processing student request", request.getRequestURI()));
    }

    private ApiErrorResponse toResponse(HttpStatus status, String message, String path) {
        return new ApiErrorResponse(
                Instant.now(),
                status.value(),
                status.getReasonPhrase(),
                message,
                path
        );
    }

    private String toFieldMessage(FieldError fieldError) {
        return fieldError.getField() + " " + (fieldError.getDefaultMessage() != null ? fieldError.getDefaultMessage() : "is invalid");
    }
}