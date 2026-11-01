package com.restofy.order.application.service;

import com.restofy.order.domain.enums.CancellationReasonCode;
import com.restofy.order.domain.enums.CancellationScope;
import com.restofy.order.domain.enums.CancellationStatus;
import com.restofy.order.domain.exception.BusinessException;
import com.restofy.order.domain.exception.ErrorCode;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;

/**
 * A validated cancellation report request. Every value that reaches the database comes from a closed set (enums,
 * bounded numbers, parsed instants); nothing from the request is ever concatenated into a query. Times are UTC: a date
 * means the start of that day, {@code from} is inclusive and {@code to} exclusive, both on the time of the request.
 */
public record CancellationReportQuery(Instant from, Instant to, CancellationReasonCode reasonCode,
                                      CancellationScope scope, CancellationStatus status, boolean overdueOnly,
                                      boolean oldestFirst, int page, int limit) {

    /** A date without a time ("2026-10-01") has this many characters; anything longer is read as a UTC instant. */
    private static final int ISO_DATE_LENGTH = "2026-10-01".length();

    public static CancellationReportQuery of(String from, String to, String reasonCode, String scope, String status,
                                             Boolean overdue, String sort, Integer page, Integer limit) {
        Instant parsedFrom = instant("from", from);
        Instant parsedTo = instant("to", to);
        if (parsedFrom != null && parsedTo != null && !parsedFrom.isBefore(parsedTo)) {
            throw invalid("to", "must be after from");
        }
        CancellationReasonCode parsedReason = null;
        if (reasonCode != null) {
            parsedReason = CancellationReasonCode.parse(reasonCode.trim().toUpperCase(Locale.ROOT))
                    .orElseThrow(() -> invalid("reasonCode", "must be a cancellation reason code"));
        }
        CancellationScope parsedScope = parseScope(scope);
        CancellationStatus parsedStatus = null;
        if (status != null) {
            try {
                parsedStatus = CancellationStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException _) {
                throw invalid("status", "must be REQUESTED, APPLIED or REJECTED");
            }
        }
        boolean overdueOnly = Boolean.TRUE.equals(overdue);
        if (overdueOnly && parsedStatus != null && parsedStatus != CancellationStatus.REQUESTED) {
            throw invalid("overdue", "only requests waiting for the kitchen (status REQUESTED) can be overdue");
        }
        boolean oldestFirst = oldestFirst(sort);
        int parsedPage = page == null ? 0 : page;
        if (parsedPage < 0) {
            throw invalid("page", "must not be negative");
        }
        int parsedLimit = limit == null ? PageLimits.DEFAULT_LIMIT : limit;
        if (parsedLimit < 1 || parsedLimit > PageLimits.MAX_LIMIT) {
            throw invalid("limit", "must be between 1 and " + PageLimits.MAX_LIMIT);
        }
        return new CancellationReportQuery(parsedFrom, parsedTo, parsedReason, parsedScope, parsedStatus, overdueOnly,
                oldestFirst, parsedPage, parsedLimit);
    }

    private static CancellationScope parseScope(String scope) {
        if (scope == null) {
            return null;
        }
        try {
            return CancellationScope.valueOf(scope.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException _) {
            throw invalid("scope", "must be FULL or PARTIAL");
        }
    }

    private static boolean oldestFirst(String sort) {
        if (sort == null) {
            return false;
        }
        return switch (sort.trim().toLowerCase(Locale.ROOT)) {
            case "requestedat,desc", "requestedat:desc", "-requestedat" -> false;
            case "requestedat,asc", "requestedat:asc", "requestedat" -> true;
            default -> throw invalid("sort", "must be requestedAt,asc or requestedAt,desc");
        };
    }

    private static Instant instant(String parameter, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        try {
            return trimmed.length() <= ISO_DATE_LENGTH ? LocalDate.parse(trimmed).atStartOfDay(ZoneOffset.UTC).toInstant()
                    : Instant.parse(trimmed);
        } catch (DateTimeParseException _) {
            throw invalid(parameter, "must be an ISO-8601 date (2026-10-01) or UTC instant (2026-10-01T00:00:00Z)");
        }
    }

    private static BusinessException invalid(String parameter, String rule) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, "Invalid query parameter '" + parameter + "'",
                Map.of("parameter", parameter, "rule", rule));
    }
}
