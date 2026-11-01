package com.restofy.order.api.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A placed order with its immutable price snapshot. Amounts are integer minor units. */
public record OrderResponse(UUID orderId, String orderNo, UUID tableSessionId, String fulfillmentStatus,
                            String paymentStatus, String currency, Amounts amounts, List<OrderItemResponse> items,
                            String note, Instant placedAt) {

    public OrderResponse {
        items = ResponseCollections.copyOf(items);
    }

    public record Amounts(long netMinor, long vatMinor, long grossMinor) {
    }

    /**
     * One ordered line. {@code quantity} is what was ordered and never changes; {@code cancelledQuantity} units were
     * cancelled and {@code remainingQuantity} are still to be prepared and paid. The price fields are omitted only in
     * the KITCHEN view of an order (the kitchen needs what to prepare, not what it costs); every other reader always
     * gets them.
     */
    public record OrderItemResponse(UUID orderItemId, UUID productId, String name, int quantity,
                                    int cancelledQuantity, int remainingQuantity,
                                    List<CartResponse.RemovedIngredient> removedIngredients,
                                    List<CartResponse.ExtraIngredient> extras, String note,
                                    @JsonInclude(JsonInclude.Include.NON_NULL) Long unitGrossMinor,
                                    @JsonInclude(JsonInclude.Include.NON_NULL) Integer taxRateBps,
                                    @JsonInclude(JsonInclude.Include.NON_NULL) Long lineGrossMinor,
                                    @JsonInclude(JsonInclude.Include.NON_NULL) Long lineNetMinor,
                                    @JsonInclude(JsonInclude.Include.NON_NULL) Long lineVatMinor) {

        public OrderItemResponse {
            removedIngredients = ResponseCollections.copyOf(removedIngredients);
            extras = ResponseCollections.copyOf(extras);
        }
    }
}
