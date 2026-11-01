package com.restofy.order.api.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The full view of one order: the immutable price snapshot, both independent statuses and what is still due.
 * {@code fulfillmentStatus} (RECEIVED...COMPLETED/CANCELLED) and {@code paymentStatus} are separate lifecycles;
 * a COMPLETED order may be unpaid and a PAID order may still be in preparation.
 *
 * <p>{@code amounts} is the immutable original total; {@code cancelledGrossMinor} is what cancellations took off it and
 * {@code activeGrossMinor} what the remaining units are worth ({@code amounts.grossMinor - cancelledGrossMinor}).
 * {@code currentDueMinor} is what is still owed: 0 once a verified payment settled the order.
 *
 * <p>The KITCHEN role gets a reduced view of its own restaurant's orders: what to prepare (items, quantities, extras,
 * removed ingredients, notes) and the fulfillment status, but no payment status, no amounts and no prices; those fields
 * are then absent from the JSON.
 */
public record OrderDetailResponse(UUID orderId, String orderNo, UUID tableId, UUID tableSessionId,
                                  String fulfillmentStatus,
                                  @JsonInclude(JsonInclude.Include.NON_NULL) String paymentStatus,
                                  @JsonInclude(JsonInclude.Include.NON_NULL) String currency,
                                  @JsonInclude(JsonInclude.Include.NON_NULL) OrderResponse.Amounts amounts,
                                  @JsonInclude(JsonInclude.Include.NON_NULL) Long currentDueMinor,
                                  @JsonInclude(JsonInclude.Include.NON_NULL) Long cancelledGrossMinor,
                                  @JsonInclude(JsonInclude.Include.NON_NULL) Long activeGrossMinor,
                                  List<OrderResponse.OrderItemResponse> items, String note, Instant placedAt,
                                  Instant updatedAt, long version) {

    public OrderDetailResponse {
        items = ResponseCollections.copyOf(items);
    }
}
