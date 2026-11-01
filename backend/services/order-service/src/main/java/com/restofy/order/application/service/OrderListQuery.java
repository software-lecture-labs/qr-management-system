package com.restofy.order.application.service;

import com.restofy.order.domain.enums.OrderView;
import com.restofy.order.domain.enums.PaymentStatus;
import com.restofy.order.domain.exception.BusinessException;
import com.restofy.order.domain.exception.ErrorCode;
import org.springframework.data.domain.Sort;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * A validated list request. Every value that reaches the database comes from a closed set (enums, bounded numbers,
 * an allow-listed sort); nothing from the request is ever concatenated into a query.
 *
 * @param tableSessionId one table SESSION (a table is used by many sessions over time)
 * @param tableId        one physical table, across all of its sessions; never interchangeable with a session id
 */
public record OrderListQuery(UUID tableSessionId, UUID tableId, OrderView view, PaymentStatus paymentStatus,
                             int page, int limit, Sort.Direction direction) {


    /** Parses and validates the raw query parameters; a bad value is a 422 with the offending parameter named. */
    public static OrderListQuery of(UUID tableSessionId, UUID tableId, String view, String paymentStatus,
                                    Integer page, Integer limit, String sort) {
        OrderView parsedView = view == null ? OrderView.ACTIVE : OrderView.parse(view)
                .orElseThrow(() -> invalid("view", "must be one of active, completed, cancelled"));
        PaymentStatus parsedPayment = null;
        if (paymentStatus != null) {
            try {
                parsedPayment = PaymentStatus.valueOf(paymentStatus.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException _) {
                throw invalid("paymentStatus", "must be one of NOT_REQUESTED, PENDING, PAID, FAILED, CANCELLED");
            }
        }
        int parsedPage = page == null ? 0 : page;
        if (parsedPage < 0) {
            throw invalid("page", "must not be negative");
        }
        int parsedLimit = limit == null ? PageLimits.DEFAULT_LIMIT : limit;
        if (parsedLimit < 1 || parsedLimit > PageLimits.MAX_LIMIT) {
            throw invalid("limit", "must be between 1 and " + PageLimits.MAX_LIMIT);
        }
        Sort.Direction direction = Sort.Direction.DESC;
        if (sort != null) {
            direction = switch (sort.trim().toLowerCase(Locale.ROOT)) {
                case "placedat,desc", "placedat:desc", "-placedat" -> Sort.Direction.DESC;
                case "placedat,asc", "placedat:asc", "placedat" -> Sort.Direction.ASC;
                default -> throw invalid("sort", "must be placedAt,asc or placedAt,desc");
            };
        }
        return new OrderListQuery(tableSessionId, tableId, parsedView, parsedPayment, parsedPage, parsedLimit,
                direction);
    }

    private static BusinessException invalid(String parameter, String rule) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, "Invalid query parameter '" + parameter + "'",
                Map.of("parameter", parameter, "rule", rule));
    }
}
