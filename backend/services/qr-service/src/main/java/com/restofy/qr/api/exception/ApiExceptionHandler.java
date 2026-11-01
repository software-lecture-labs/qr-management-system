package com.restofy.qr.api.exception;

import com.restofy.qr.domain.exception.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(StaffClosureException.class)
    ProblemDetail staffClosure(StaffClosureException exception) {
        var detail = problem(exception.status(), exception.getMessage());
        detail.setProperty("code", exception.code().name());
        detail.setProperty("blockers", java.util.List.of());
        return detail;
    }

    @ExceptionHandler({QrImageUnavailableException.class, SessionClosureUnavailableException.class})
    ProblemDetail unavailable(RuntimeException exception) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage());
    }

    @ExceptionHandler({TableNotFoundException.class, QrCodeNotFoundException.class,
            InvalidQrCodeException.class, InvalidGuestSessionException.class})
    ProblemDetail notFound(RuntimeException exception) {
        return problem(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler({TableConflictException.class, OptimisticLockingFailureException.class, DataIntegrityViolationException.class})
    ProblemDetail conflict(Exception exception) {
        return problem(HttpStatus.CONFLICT, "The operation conflicts with the current data. Reload and retry.");
    }

    @ExceptionHandler({QrCodeConflictException.class, TableSessionConflictException.class, SessionClosureConflictException.class})
    ProblemDetail qrConflict(RuntimeException exception) {
        return problem(HttpStatus.CONFLICT, exception.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalid(IllegalArgumentException exception) {
        return problem(HttpStatus.BAD_REQUEST, "Request validation failed.");
    }

    @ExceptionHandler(AuthenticationException.class)
    ProblemDetail unauthenticated(AuthenticationException exception) {
        return problem(HttpStatus.UNAUTHORIZED, "Verified management context is required.");
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail forbidden(AccessDeniedException exception) {
        return problem(HttpStatus.FORBIDDEN, "Table management access is unavailable.");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception exception) {
        // Exception messages, causes and stack traces can contain credentials or SQL.
        LOG.error("event=QR_UNEXPECTED_ERROR type={}", exception.getClass().getName());
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "The request could not be completed.");
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body,
            HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        return super.handleExceptionInternal(exception,
                ProblemDetail.forStatusAndDetail(statusCode, "Request could not be processed."), headers, statusCode, request);
    }

    private static ProblemDetail problem(HttpStatus status, String detail) {
        return ProblemDetail.forStatusAndDetail(status, detail);
    }
}
