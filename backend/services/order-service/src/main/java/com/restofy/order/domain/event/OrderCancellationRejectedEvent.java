package com.restofy.order.domain.event;

import com.restofy.order.domain.event.CancellationEventParts.Actor;
import com.restofy.order.domain.event.CancellationEventParts.Item;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Versioned (schemaVersion 1) event: a cancellation request was REJECTED. The order and its amounts did not change.
 * Consumers deduplicate on {@code eventId}. No dispatcher delivers it yet (see the outbox).
 */
public record OrderCancellationRejectedEvent(UUID eventId, String eventType, int schemaVersion, Instant occurredAt,
                                             String correlationId, UUID restaurantId, UUID orderId, String orderNo,
                                             UUID tableId, UUID tableSessionId, String fulfillmentStatus,
                                             String paymentStatus, UUID cancellationId, String scope,
                                             List<Item> items, Actor requestedBy, Actor rejectedBy,
                                             String rejectionCode, String rejectionNote, Instant decidedAt) {

    public static final String TYPE = "OrderCancellationRejected";
    public static final int SCHEMA_VERSION = 1;
}
