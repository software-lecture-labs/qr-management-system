package com.restofy.order.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Versioned (schemaVersion 1) event announcing that an order moved one step along its fulfillment lifecycle. Only
 * internal ids, statuses and the kind of actor are carried: never a staff user id, token or payment data. Consumers
 * must deduplicate on {@code eventId}. This is the contract; no dispatcher delivers it yet (see the outbox).
 */
public record OrderFulfillmentStatusChangedEvent(UUID eventId, String eventType, int schemaVersion, Instant occurredAt,
                                                 String correlationId, UUID restaurantId, UUID tableId,
                                                 UUID tableSessionId, UUID orderId, String orderNo,
                                                 String fromStatus, String toStatus, String actorType,
                                                 String actorRole, String source) {

    public static final String TYPE = "OrderFulfillmentStatusChanged";
    public static final int SCHEMA_VERSION = 1;
}
