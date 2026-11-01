package com.restofy.order.api.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One page of orders. Sorted by {@code placedAt} (default newest first) with the order id as tie-breaker, so paging
 * is stable. {@code view} filters on the fulfillment status only; payment is independent (see {@code paymentStatus}).
 */
public record OrderListResponse(List<Summary> items, int page, int limit, long totalItems, int totalPages,
                                String view) {

    public OrderListResponse {
        items = ResponseCollections.copyOf(items);
    }

    /** The payment and amount fields are absent only in the KITCHEN view; unitCount counts the still-active units. */
    public record Summary(UUID orderId, String orderNo, UUID tableId, UUID tableSessionId, String fulfillmentStatus,
                          @JsonInclude(JsonInclude.Include.NON_NULL) String paymentStatus,
                          @JsonInclude(JsonInclude.Include.NON_NULL) String currency,
                          @JsonInclude(JsonInclude.Include.NON_NULL) Long grossMinor,
                          @JsonInclude(JsonInclude.Include.NON_NULL) Long currentDueMinor, long unitCount,
                          Instant placedAt) {
    }
}
