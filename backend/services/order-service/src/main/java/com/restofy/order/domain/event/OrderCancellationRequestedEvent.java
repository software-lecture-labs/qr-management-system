package com.restofy.order.domain.event;

import com.restofy.order.domain.event.CancellationEventParts.Actor;
import com.restofy.order.domain.event.CancellationEventParts.Item;
import com.restofy.order.domain.event.CancellationEventParts.Reason;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Versioned (schemaVersion 1) event: a cancellation was REQUESTED. Nothing is cancelled yet; whether it will be is told
 * later by {@code OrderCancelled}, {@code OrderItemsCancelled} or {@code OrderCancellationRejected}. When
 * {@code kitchenConfirmationRequired} is true the order is already in preparation and the Kitchen service has to answer.
 * {@code projectedDueMinor} is what the order would still owe if the request is applied. Consumers deduplicate on
 * {@code eventId}. No dispatcher delivers it yet (see the outbox).
 */
public record OrderCancellationRequestedEvent(UUID eventId, String eventType, int schemaVersion, Instant occurredAt,
                                              String correlationId, UUID restaurantId, UUID orderId, String orderNo,
                                              UUID tableId, UUID tableSessionId, String fulfillmentStatus,
                                              String paymentStatus, UUID cancellationId, String scope,
                                              boolean kitchenConfirmationRequired, List<Item> items,
                                              List<Reason> reasons, Actor requestedBy, long previousDueMinor,
                                              long projectedDueMinor, String currency) {

    public static final String TYPE = "OrderCancellationRequested";
    public static final int SCHEMA_VERSION = 1;
}
