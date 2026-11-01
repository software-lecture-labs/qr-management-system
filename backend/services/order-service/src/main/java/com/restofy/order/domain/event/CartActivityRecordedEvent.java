package com.restofy.order.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Versioned (schemaVersion 1) event telling the QR Service that a guest really worked on their cart, which refreshes
 * the 15-minute idle clock of that guest and their table session (ADR-QR-001 K6). Only a successful change of the
 * cart counts: reading the cart, an idempotent replay or a refused change never produce it. It carries no product,
 * price, note or token, just who (internal ids) and what kind of activity. Consumers must deduplicate on
 * {@code eventId}, and a late event never reopens a closed or expired session. The contract is a draft: no consumer
 * receives it yet (see the outbox).
 */
public record CartActivityRecordedEvent(UUID eventId, String eventType, int schemaVersion, Instant occurredAt,
                                        String correlationId, UUID restaurantId, UUID tableSessionId,
                                        UUID guestSessionId, UUID cartId, Activity activity) {

    public static final String TYPE = "CartActivityRecorded";
    public static final int SCHEMA_VERSION = 1;

    public enum Activity {
        ITEM_ADDED,
        ITEM_QUANTITY_CHANGED,
        ITEM_REMOVED,
        CART_CLEARED
    }
}
