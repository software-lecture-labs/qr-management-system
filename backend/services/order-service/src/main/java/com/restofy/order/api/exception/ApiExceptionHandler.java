package com.restofy.order.api.exception;

import com.restofy.order.domain.exception.BusinessException;
import com.restofy.order.domain.exception.ErrorCode;
import com.restofy.order.infrastructure.config.CorrelationIdFilter;
import jakarta.persistence.OptimisticLockException;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * Maps every failure to the single {@link ErrorResponse} body. Framework exceptions get fixed, safe messages;
 * unexpected errors are logged with their stack trace and reported as a generic 500.
 *
 * <p><b>Logging policy.</b> The level says how much attention the event deserves, not which HTTP status it has: a 4xx
 * is not automatically a warning and a 409 is not automatically an error. Each exception is logged exactly once, here
 * (the security filters log their own failures and never reach this class). The correlation id is added to every line
 * by the logging pattern. A line carries only the stable error code, an exception class or a database constraint
 * name; never the request body, headers, tokens, user text, SQL or SQL parameters.
 * <table>
 *   <caption>Level by event</caption>
 *   <tr><th>Event</th><th>Level</th></tr>
 *   <tr><td>Expected business rejection (4xx, including 409 conflicts, idempotency and version conflicts)</td>
 *       <td>INFO</td></tr>
 *   <tr><td>Request validation or malformed request</td><td>DEBUG</td></tr>
 *   <tr><td>401 / 403 raised by a use case (suspicious or unauthorized access)</td><td>WARN</td></tr>
 *   <tr><td>Dependency unavailable (503)</td><td>WARN</td></tr>
 *   <tr><td>Integrity violation that reached the API: a unique-key race nobody anticipated</td><td>WARN</td></tr>
 *   <tr><td>Integrity violation of any other kind (check, foreign key, not null): the code tried to write data the
 *       database refuses</td><td>ERROR</td></tr>
 *   <tr><td>Unexpected exception, any 5xx</td><td>ERROR (with stack trace)</td></tr>
 * </table>
 */
@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    /** SQLState class 23 is "integrity constraint violation"; this one is the unique violation. */
    private static final String SQL_STATE_UNIQUE_VIOLATION = "23505";

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<Object> handleBusiness(BusinessException ex) {
        ErrorCode code = ex.code();
        switch (code) {
            case INTERNAL_ERROR -> log.error("Request failed: {} ({})", code, oneLine(ex.getMessage()), ex);
            case DEPENDENCY_UNAVAILABLE ->
                    log.warn("Dependency unavailable: {} ({})", code, oneLine(ex.getMessage()));
            case UNAUTHENTICATED, ACCESS_DENIED -> log.warn("Access refused: {}", code);
            default -> log.info("Request rejected: {}", code);
        }
        return respond(code, ex.getMessage(), ex.details());
    }

    @ExceptionHandler({ObjectOptimisticLockingFailureException.class, OptimisticLockException.class})
    ResponseEntity<Object> handleOptimisticLock(Exception ex) {
        log.info("Concurrent modification rejected");
        return respond(ErrorCode.CART_VERSION_CONFLICT,
                "The cart was changed in the meantime; reload it and try again", null);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Object> handleIntegrity(DataIntegrityViolationException ex) {
        String sqlState = sqlStateOf(ex);
        String constraint = constraintOf(ex);
        // Neither the exception message nor its stack trace is logged: the database message quotes the offending
        // key values and the SQL statement.
        if (SQL_STATE_UNIQUE_VIOLATION.equals(sqlState)) {
            log.warn("Unanticipated unique-key conflict rejected: constraint={} sqlState={}", constraint, sqlState);
        } else {
            log.error("Integrity violation rejected: constraint={} sqlState={} cause={}", constraint, sqlState,
                    rootOf(ex).getClass().getName());
        }
        return respond(ErrorCode.CONFLICT, "The request conflicts with the current state, please retry", null);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception ex) {
        log.error("Unexpected error", ex);
        return respond(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred", null);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        List<Map<String, String>> fields = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of("field", error.getField(),
                        "message", String.valueOf(error.getDefaultMessage())))
                .toList();
        log.debug("Request failed validation: fields={}", fields.stream().map(field -> field.get("field")).toList());
        return respond(ErrorCode.VALIDATION_ERROR, "The request failed validation", Map.of("fields", fields));
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
                                                                           HttpHeaders headers, HttpStatusCode status,
                                                                           WebRequest request) {
        log.debug("Request failed validation");
        return respond(ErrorCode.VALIDATION_ERROR, "The request failed validation", null);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        ErrorCode code = frameworkCode(statusCode);
        if (statusCode.is5xxServerError()) {
            log.error("Framework error {}", statusCode.value(), ex);
        } else {
            log.debug("Request rejected by the framework: status={} cause={}", statusCode.value(),
                    ex.getClass().getSimpleName());
        }
        String message = switch (code) {
            case INVALID_REQUEST -> "The request is malformed or contains unsupported fields";
            case NOT_FOUND -> "The resource does not exist";
            case METHOD_NOT_ALLOWED -> "The HTTP method is not supported for this resource";
            case UNSUPPORTED_MEDIA_TYPE -> "The content type is not supported";
            default -> "An unexpected error occurred";
        };
        ResponseEntity<Object> response = respond(code, message, null);
        return ResponseEntity.status(statusCode).headers(headers).body(response.getBody());
    }

    private static ErrorCode frameworkCode(HttpStatusCode status) {
        if (status.isSameCodeAs(HttpStatus.NOT_FOUND)) {
            return ErrorCode.NOT_FOUND;
        }
        if (status.isSameCodeAs(HttpStatus.METHOD_NOT_ALLOWED)) {
            return ErrorCode.METHOD_NOT_ALLOWED;
        }
        if (status.isSameCodeAs(HttpStatus.UNSUPPORTED_MEDIA_TYPE)) {
            return ErrorCode.UNSUPPORTED_MEDIA_TYPE;
        }
        return status.is5xxServerError() ? ErrorCode.INTERNAL_ERROR : ErrorCode.INVALID_REQUEST;
    }

    private static String sqlStateOf(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return "unknown";
    }

    private static String constraintOf(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation && violation.getConstraintName() != null) {
                return violation.getConstraintName();
            }
        }
        return "unknown";
    }

    /** A value that goes into a log line may not break it: control characters (line breaks above all) become '_'. */
    private static String oneLine(String text) {
        return text == null ? "" : text.replaceAll("\\p{Cntrl}", "_");
    }

    private static Throwable rootOf(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root;
    }

    private static ResponseEntity<Object> respond(ErrorCode code, String message, Map<String, Object> details) {
        ErrorResponse body = ErrorResponse.of(code, message, details, MDC.get(CorrelationIdFilter.MDC_KEY));
        return ResponseEntity.status(HttpStatus.valueOf(code.httpStatus())).body(body);
    }
}
