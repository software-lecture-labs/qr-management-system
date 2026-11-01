package com.restofy.order.domain.event;

import com.restofy.order.domain.event.CancellationEventParts.Actor;
import com.restofy.order.domain.event.CancellationEventParts.Item;
import com.restofy.order.domain.event.CancellationEventParts.Reason;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Versioned (schemaVersion 1) event: the WHOLE order was cancelled, finally. {@code refundRequiredMinor} is the refund
 * this cancellation requires (the verified collected amount minus what the order is worth now); {@code
 * refundReviewRequired} says that people and the Payment Service have to decide it. The Order Service never performs or
 * records a refund. Consumers deduplicate on {@code eventId}. No dispatcher delivers it yet (see the outbox).
 */
public record OrderCancelledEvent(UUID eventId, String eventType, int schemaVersion, Instant occurredAt,
                                  String correlationId, UUID restaurantId, UUID orderId, String orderNo, UUID tableId,
                                  UUID tableSessionId, String fulfillmentStatus, String paymentStatus,
                                  UUID cancellationId, List<Item> items, List<Reason> reasons, Actor requestedBy,
                                  Actor decidedBy, Instant decidedAt, String previousFulfillmentStatus,
                                  long previousDueMinor, long newDueMinor, long cancelledAmountMinor, String currency,
                                  long refundRequiredMinor, boolean refundReviewRequired) {

    public static final String TYPE = "OrderCancelled";
    public static final int SCHEMA_VERSION = 1;
}
