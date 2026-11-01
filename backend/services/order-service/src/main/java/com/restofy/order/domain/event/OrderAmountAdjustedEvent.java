package com.restofy.order.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Versioned (schemaVersion 1) event: what the order still owes changed because of a finalised cancellation. It is
 * written in the same transaction as {@code OrderCancelled} / {@code OrderItemsCancelled} and is what the Payment
 * Service acts on (an open payment of the old amount, a refund review). {@code originalGrossMinor} never changes;
 * {@code cancelledTotalMinor} is everything cancelled so far; {@code refundRequiredMinor} is the refund this
 * cancellation requires. Consumers deduplicate on {@code eventId}. No dispatcher delivers it yet (see the outbox).
 */
public record OrderAmountAdjustedEvent(UUID eventId, String eventType, int schemaVersion, Instant occurredAt,
                                       String correlationId, UUID restaurantId, UUID orderId, String orderNo,
                                       UUID tableId, UUID tableSessionId, String fulfillmentStatus,
                                       String paymentStatus, UUID cancellationId, String adjustmentReason,
                                       long previousDueMinor, long newDueMinor, long originalGrossMinor,
                                       long cancelledTotalMinor, String currency, String paymentStatusAtAdjustment,
                                       long refundRequiredMinor) {

    public static final String TYPE = "OrderAmountAdjusted";
    public static final int SCHEMA_VERSION = 1;
}
