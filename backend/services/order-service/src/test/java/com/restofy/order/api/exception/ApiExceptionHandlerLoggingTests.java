package com.restofy.order.api.exception;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.restofy.order.domain.exception.BusinessException;
import com.restofy.order.domain.exception.ErrorCode;
import com.restofy.order.infrastructure.config.CorrelationIdFilter;
import jakarta.persistence.OptimisticLockException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The logging policy of {@link ApiExceptionHandler}: the level follows the kind of event, never the HTTP status; one
 * event is logged once; nothing sensitive (SQL, key values, request data, secrets) reaches the log or the response.
 */
class ApiExceptionHandlerLoggingTests {

    private static final String SECRET = "hunter2-secret-token";

    private final ApiExceptionHandler handler = new ApiExceptionHandler();
    private final Logger logger = (Logger) LoggerFactory.getLogger(ApiExceptionHandler.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Level originalLevel;

    @BeforeEach
    void captureLogs() {
        originalLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        logs.start();
        logger.addAppender(logs);
        MDC.put(CorrelationIdFilter.MDC_KEY, "corr-123");
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(logs);
        logger.setLevel(originalLevel);
        MDC.remove(CorrelationIdFilter.MDC_KEY);
    }

    private ILoggingEvent onlyEvent() {
        assertEquals(1, logs.list.size(), "an event is logged exactly once: " + logs.list);
        return logs.list.getFirst();
    }

    private static ErrorResponse body(ResponseEntity<Object> response) {
        return (ErrorResponse) response.getBody();
    }

    private static Level expectedLevel(ErrorCode code) {
        return switch (code) {
            case INTERNAL_ERROR -> Level.ERROR;
            case DEPENDENCY_UNAVAILABLE, UNAUTHENTICATED, ACCESS_DENIED -> Level.WARN;
            default -> Level.INFO;
        };
    }

    // ---- business rejections --------------------------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    void everyBusinessErrorIsLoggedOnceAtTheLevelOfItsKindAndAnsweredWithItsOwnStatus(ErrorCode code) {
        ResponseEntity<Object> response = handler.handleBusiness(new BusinessException(code, "safe message"));

        ILoggingEvent event = onlyEvent();
        assertEquals(expectedLevel(code), event.getLevel(), code.name());
        assertTrue(event.getFormattedMessage().contains(code.name()), event.getFormattedMessage());
        assertEquals(code.httpStatus(), response.getStatusCode().value());
        assertEquals(code.name(), body(response).code());
        assertEquals("corr-123", body(response).correlationId());
    }

    @Test
    void expectedConflictsAndRejectionsAreNeitherWarningsNorErrors() {
        for (ErrorCode code : List.of(ErrorCode.CART_VERSION_CONFLICT, ErrorCode.IDEMPOTENCY_CONFLICT,
                ErrorCode.PRICE_CHANGED, ErrorCode.TABLE_SESSION_NOT_ACTIVE, ErrorCode.CANCELLATION_NOT_ALLOWED,
                ErrorCode.VALIDATION_ERROR, ErrorCode.ORDER_NOT_FOUND, ErrorCode.INVALID_STATUS_TRANSITION)) {
            logs.list.clear();
            handler.handleBusiness(new BusinessException(code, "rule"));
            assertEquals(Level.INFO, onlyEvent().getLevel(), code.name());
        }
    }

    @Test
    void aBusinessRejectionNeverWritesItsMessageOrDetailsToTheLog() {
        handler.handleBusiness(new BusinessException(ErrorCode.PRODUCT_UNAVAILABLE, "note says " + SECRET,
                Map.of("note", SECRET)));

        ILoggingEvent event = onlyEvent();
        assertFalse(event.getFormattedMessage().contains(SECRET));
        assertNull(event.getThrowableProxy());
    }

    @Test
    void aServerSideBusinessFailureKeepsItsStackTraceAndCannotBreakTheLogLine() {
        BusinessException failure = new BusinessException(ErrorCode.INTERNAL_ERROR, "line one\nINFO forged entry");

        handler.handleBusiness(failure);

        ILoggingEvent event = onlyEvent();
        assertEquals(Level.ERROR, event.getLevel());
        assertFalse(event.getFormattedMessage().contains("\n"), "a line break would forge a second log entry");
        assertNotNull(event.getThrowableProxy(), "a 5xx must be diagnosable");
    }

    @Test
    void aDependencyOutageIsAWarningWithoutTheStackTrace() {
        ResponseEntity<Object> response = handler.handleBusiness(
                new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "The menu service did not answer"));

        ILoggingEvent event = onlyEvent();
        assertEquals(Level.WARN, event.getLevel());
        assertNull(event.getThrowableProxy());
        assertEquals(503, response.getStatusCode().value());
    }

    // ---- concurrency ------------------------------------------------------------------------------------------------

    @Test
    void anOptimisticLockConflictIsAnExpectedInfoAnd409() {
        for (Exception conflict : List.of(new ObjectOptimisticLockingFailureException(Object.class, "id"),
                new OptimisticLockException("stale " + SECRET))) {
            logs.list.clear();

            ResponseEntity<Object> response = handler.handleOptimisticLock(conflict);

            ILoggingEvent event = onlyEvent();
            assertEquals(Level.INFO, event.getLevel());
            assertFalse(event.getFormattedMessage().contains(SECRET));
            assertEquals(409, response.getStatusCode().value());
            assertEquals("CART_VERSION_CONFLICT", body(response).code());
        }
    }

    // ---- integrity violations -----------------------------------------------------------------------------------------

    private static DataIntegrityViolationException integrity(String sqlState, String constraint) {
        SQLException sql = new SQLException("ERROR: violates " + constraint + " Key (idempotency_key)=(" + SECRET
                + ") INSERT INTO idempotency_records VALUES ('" + SECRET + "')", sqlState);
        Throwable cause = constraint == null ? sql : new ConstraintViolationException("could not execute statement",
                sql, constraint);
        return new DataIntegrityViolationException("could not execute statement [" + sql.getMessage() + "]", cause);
    }

    @Test
    void anUnanticipatedUniqueKeyRaceIsAWarningThatNamesTheConstraintOnly() {
        ResponseEntity<Object> response = handler.handleIntegrity(integrity("23505", "uq_idempotency_records_key"));

        ILoggingEvent event = onlyEvent();
        assertEquals(Level.WARN, event.getLevel());
        assertTrue(event.getFormattedMessage().contains("uq_idempotency_records_key"));
        assertTrue(event.getFormattedMessage().contains("23505"));
        assertNoLeak(event);
        assertEquals(409, response.getStatusCode().value());
        assertEquals("CONFLICT", body(response).code());
    }

    @Test
    void aCheckForeignKeyOrNotNullViolationIsAnErrorBecauseTheCodeWroteRefusedData() {
        for (String state : List.of("23514", "23503", "23502")) {
            logs.list.clear();

            ResponseEntity<Object> response = handler.handleIntegrity(integrity(state, "ck_something"));

            ILoggingEvent event = onlyEvent();
            assertEquals(Level.ERROR, event.getLevel(), state);
            assertTrue(event.getFormattedMessage().contains(state));
            assertNoLeak(event);
            assertEquals(409, response.getStatusCode().value(), "the HTTP answer is unchanged");
            assertEquals("CONFLICT", body(response).code());
        }
    }

    @Test
    void anIntegrityViolationWithoutDriverDetailsIsStillLoggedAndNeverLeaks() {
        handler.handleIntegrity(new DataIntegrityViolationException("INSERT ... " + SECRET));

        ILoggingEvent event = onlyEvent();
        assertEquals(Level.ERROR, event.getLevel());
        assertTrue(event.getFormattedMessage().contains("unknown"));
        assertNoLeak(event);
    }

    private static void assertNoLeak(ILoggingEvent event) {
        assertFalse(event.getFormattedMessage().contains(SECRET), "key values are not logged");
        assertFalse(event.getFormattedMessage().toUpperCase().contains("INSERT"), "SQL is not logged");
        assertNull(event.getThrowableProxy(), "the exception message quotes SQL and values, so no stack trace");
    }

    // ---- unexpected errors ----------------------------------------------------------------------------------------------

    @Test
    void anUnexpectedExceptionIsAnErrorWithItsStackTraceAndAGenericAnswer() {
        ResponseEntity<Object> response = handler.handleUnexpected(new IllegalStateException("db password=" + SECRET));

        ILoggingEvent event = onlyEvent();
        assertEquals(Level.ERROR, event.getLevel());
        assertNotNull(event.getThrowableProxy(), "a 5xx must be diagnosable");
        assertEquals(500, response.getStatusCode().value());
        assertEquals("INTERNAL_ERROR", body(response).code());
        assertEquals("An unexpected error occurred", body(response).message());
        assertFalse(String.valueOf(response.getBody()).contains(SECRET), "the answer never carries the cause");
        assertEquals("corr-123", body(response).correlationId());
    }

    // ---- framework and validation errors ---------------------------------------------------------------------------------

    @Test
    void clientMistakesDetectedByTheFrameworkAreDebugAndKeepTheirStatusAndCode() {
        record Case(Exception error, HttpStatus status, String code) {
        }
        List<Case> cases = List.of(
                new Case(new HttpRequestMethodNotSupportedException("PUT"), HttpStatus.METHOD_NOT_ALLOWED,
                        "METHOD_NOT_ALLOWED"),
                new Case(new HttpMediaTypeNotSupportedException("text/plain"), HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                        "UNSUPPORTED_MEDIA_TYPE"),
                new Case(new IllegalArgumentException("bad"), HttpStatus.NOT_FOUND, "NOT_FOUND"),
                new Case(new IllegalArgumentException("bad"), HttpStatus.BAD_REQUEST, "INVALID_REQUEST"));
        for (Case c : cases) {
            logs.list.clear();

            ResponseEntity<Object> response = handler.handleExceptionInternal(c.error(), null, new HttpHeaders(),
                    c.status(), null);

            assertEquals(Level.DEBUG, onlyEvent().getLevel(), c.code());
            assertEquals(c.status().value(), response.getStatusCode().value());
            assertEquals(c.code(), body(response).code());
        }
    }

    @Test
    void aFrameworkServerErrorIsAnErrorWithItsStackTraceAndAGenericAnswer() {
        ResponseEntity<Object> response = handler.handleExceptionInternal(new IllegalStateException(SECRET), null,
                new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, null);

        ILoggingEvent event = onlyEvent();
        assertEquals(Level.ERROR, event.getLevel());
        assertNotNull(event.getThrowableProxy());
        assertEquals(500, response.getStatusCode().value());
        assertEquals("INTERNAL_ERROR", body(response).code());
        assertFalse(String.valueOf(response.getBody()).contains(SECRET));
    }

    @Test
    void aValidationFailureIsDebugNamesTheFieldsAndNeverLogsTheRejectedValue() throws Exception {
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new Object(), "request");
        binding.addError(new FieldError("request", "note", "typed by the guest " + SECRET, false, null, null,
                "size must be between 0 and 300"));
        MethodParameter parameter = new MethodParameter(Object.class.getMethod("toString"), -1);

        ResponseEntity<Object> response = handler.handleMethodArgumentNotValid(
                new MethodArgumentNotValidException(parameter, binding), new HttpHeaders(), HttpStatus.BAD_REQUEST,
                null);

        ILoggingEvent event = onlyEvent();
        assertEquals(Level.DEBUG, event.getLevel());
        assertTrue(event.getFormattedMessage().contains("note"));
        assertFalse(event.getFormattedMessage().contains(SECRET));
        assertEquals(422, response.getStatusCode().value());
        assertEquals("VALIDATION_ERROR", body(response).code());
        assertFalse(String.valueOf(response.getBody()).contains(SECRET), "the rejected value is not echoed back");
    }
}
