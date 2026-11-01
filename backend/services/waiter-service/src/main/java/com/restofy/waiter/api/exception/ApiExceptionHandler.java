package com.restofy.waiter.api.exception;

import com.restofy.waiter.domain.exception.CallConflictException;
import com.restofy.waiter.domain.exception.CallRateLimitedException;
import com.restofy.waiter.domain.exception.DependencyException;
import com.restofy.waiter.domain.exception.DependencyFailure;
import com.restofy.waiter.domain.exception.InvalidRequestException;
import com.restofy.waiter.domain.exception.ResourceNotFoundException;
import com.restofy.waiter.domain.exception.ServeRejectedException;
import com.restofy.waiter.domain.exception.ServeResultUnknownException;
import com.restofy.waiter.domain.exception.SnapshotLimitExceededException;
import com.restofy.waiter.domain.exception.TableCommandRejectedException;
import com.restofy.waiter.domain.exception.TableCommandUnknownException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * The single error contract: RFC 9457 problem details with a stable {@code code} property. Messages never
 * contain stack traces, SQL, hosts, URLs, tokens or personal data. Timeouts, bad downstream answers,
 * unavailable dependencies and missing resources use different statuses and codes on purpose.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<Object> authenticationRequired(AuthenticationException error, WebRequest request) {
        return problem(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "Authentication is required.", request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Object> accessDenied(AccessDeniedException error, WebRequest request) {
        return problem(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "You are not allowed to perform this operation.", request);
    }

    @ExceptionHandler(InvalidRequestException.class)
    ResponseEntity<Object> invalidRequest(InvalidRequestException error, WebRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", error.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<Object> invalidParameter(MethodArgumentTypeMismatchException error, WebRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                "Parameter '" + error.getName() + "' has an unsupported value.", request);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<Object> notFound(ResourceNotFoundException error, WebRequest request) {
        return problem(HttpStatus.NOT_FOUND, error.code(), error.getMessage(), request);
    }

    @ExceptionHandler(DependencyException.class)
    ResponseEntity<Object> dependency(DependencyException error, WebRequest request) {
        HttpStatus status = statusOf(error.failure());
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, error.getMessage());
        body.setProperty("code", "DEPENDENCY_" + error.failure());
        body.setProperty("dependency", error.dependency());
        return handleExceptionInternal(error, body, new HttpHeaders(), status, request);
    }

    @ExceptionHandler(CallConflictException.class)
    ResponseEntity<Object> conflict(CallConflictException error, WebRequest request) {
        return problem(HttpStatus.CONFLICT, error.code(), error.getMessage(), request);
    }

    @ExceptionHandler(CallRateLimitedException.class)
    ResponseEntity<Object> rateLimited(CallRateLimitedException error, WebRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(error.retryAfterSeconds()));
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, error.getMessage());
        body.setProperty("code", "CALL_RATE_LIMITED");
        return handleExceptionInternal(error, body, headers, HttpStatus.TOO_MANY_REQUESTS, request);
    }

    @ExceptionHandler(ServeRejectedException.class)
    ResponseEntity<Object> serveRejected(ServeRejectedException error, WebRequest request) {
        HttpStatus status = HttpStatus.valueOf(error.status());
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, error.getMessage());
        body.setProperty("code", error.code());
        body.setProperty("commandId", error.commandId());
        return handleExceptionInternal(error, body, new HttpHeaders(), status, request);
    }

    /** The command may have been applied: the status says what failed, the code says that the result is unknown. */
    @ExceptionHandler(ServeResultUnknownException.class)
    ResponseEntity<Object> serveUnknown(ServeResultUnknownException error, WebRequest request) {
        HttpStatus status = statusOf(error.failure());
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, error.getMessage());
        body.setProperty("code", "SERVE_RESULT_UNKNOWN");
        body.setProperty("commandId", error.commandId());
        body.setProperty("dependency", "order");
        return handleExceptionInternal(error, body, new HttpHeaders(), status, request);
    }

    @ExceptionHandler(TableCommandRejectedException.class)
    ResponseEntity<Object> tableCommandRejected(TableCommandRejectedException error, WebRequest request) {
        HttpStatus status = HttpStatus.valueOf(error.status());
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, error.getMessage());
        body.setProperty("code", error.code());
        body.setProperty("commandId", error.commandId());
        body.setProperty("blockers", error.blockers());
        return handleExceptionInternal(error, body, new HttpHeaders(), status, request);
    }

    /** The command may have been applied: the status says what failed, the code says that the result is unknown. */
    @ExceptionHandler(TableCommandUnknownException.class)
    ResponseEntity<Object> tableCommandUnknown(TableCommandUnknownException error, WebRequest request) {
        HttpStatus status = statusOf(error.failure());
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, error.getMessage());
        body.setProperty("code", error.type().name() + "_RESULT_UNKNOWN");
        body.setProperty("commandId", error.commandId());
        body.setProperty("dependency", "qr");
        return handleExceptionInternal(error, body, new HttpHeaders(), status, request);
    }

    @ExceptionHandler(SnapshotLimitExceededException.class)
    ResponseEntity<Object> snapshotLimit(SnapshotLimitExceededException error, WebRequest request) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "TABLE_SNAPSHOT_LIMIT_EXCEEDED", error.getMessage(), request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpected(Exception error, WebRequest request) {
        log.error("Unexpected error while handling a waiter request", error);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "The request could not be processed.", request);
    }

    /**
     * Standard Spring MVC failures (unknown path, unsupported method, unreadable input) keep their status and
     * get the same {@code code} property as every other error of this API.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception error, Object body, HttpHeaders headers,
                                                             HttpStatusCode status, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(error, body, headers, status, request);
        if (response != null && response.getBody() instanceof ProblemDetail detail
                && (detail.getProperties() == null || !detail.getProperties().containsKey("code"))) {
            detail.setProperty("code", status.is4xxClientError() ? "INVALID_REQUEST" : "INTERNAL_ERROR");
        }
        return response;
    }

    private static HttpStatus statusOf(DependencyFailure failure) {
        return switch (failure) {
            case TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case REJECTED, BAD_RESPONSE -> HttpStatus.BAD_GATEWAY;
        };
    }

    private ResponseEntity<Object> problem(HttpStatus status, String code, String detail, WebRequest request) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setProperty("code", code);
        return handleExceptionInternal(new IllegalStateException(code), body, new HttpHeaders(), status, request);
    }
}
