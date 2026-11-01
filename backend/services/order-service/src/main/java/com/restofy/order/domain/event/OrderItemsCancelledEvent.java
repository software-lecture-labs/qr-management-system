package com.restofy.order.domain.event;

import com.restofy.order.domain.event.CancellationEventParts.Actor;
import com.restofy.order.domain.event.CancellationEventParts.Item;
import com.restofy.order.domain.event.CancellationEventParts.Reason;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Versioned (schemaVersion 1) event: SOME units of the order were cancelled, finally, and the order goes on with the
 * rest. Each item says how many units were cancelled and how many are still active. Consumers deduplicate on
 * {@code eventId}. No dispatcher delivers it yet (see the outbox).
 */
public record OrderItemsCancelledEvent(UUID eventId, String eventType, int schemaVersion, Instant occurredAt,
                                       String correlationId, UUID restaurantId, UUID orderId, String orderNo,
                                       UUID tableId, UUID tableSessionId, String fulfillmentStatus,
                                       String paymentStatus, UUID cancellationId, List<Item> items,
                                       List<Reason> reasons, Actor requestedBy, Actor decidedBy, Instant decidedAt,
                                       long previousDueMinor, long newDueMinor, long cancelledAmountMinor,
                                       String currency, long refundRequiredMinor) {

    public static final String TYPE = "OrderItemsCancelled";
    public static final int SCHEMA_VERSION = 1;
}
