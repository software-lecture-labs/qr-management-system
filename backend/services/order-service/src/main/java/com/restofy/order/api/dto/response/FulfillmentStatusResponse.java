package com.restofy.order.api.dto.response;

import java.time.Instant;
import java.util.UUID;

/**
 * Result of a status change. {@code changed=false} means the order was already in that status because of the same
 * caller (a safe retry), so nothing was written. Fulfillment and payment are reported separately.
 */
public record FulfillmentStatusResponse(UUID orderId, String orderNo, String previousFulfillmentStatus,
                                        String fulfillmentStatus, String paymentStatus, boolean changed,
                                        Instant updatedAt) {
}
