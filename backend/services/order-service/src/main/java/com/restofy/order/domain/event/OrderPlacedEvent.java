package com.restofy.order.domain.event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Versioned (schemaVersion 1) event announcing a new order. It carries what Kitchen, QR, Notification and Waiter
 * need and nothing sensitive: internal ids and snapshots, never guest tokens or payment data. Consumers must
 * deduplicate on {@code eventId}. This is the contract; no consumer receives it yet (see the outbox).
 */
public record OrderPlacedEvent(UUID eventId, String eventType, int schemaVersion, Instant occurredAt,
                               String correlationId, UUID restaurantId, UUID tableId, UUID tableSessionId,
                               UUID guestSessionId, UUID orderId, String orderNo, String fulfillmentStatus,
                               String currency, Amounts amounts, String note, Instant placedAt, List<Item> items) {

    public static final String TYPE = "OrderPlaced";
    public static final int SCHEMA_VERSION = 1;

    public record Amounts(long netMinor, long vatMinor, long grossMinor) {
    }

    public record Item(UUID orderItemId, UUID productId, String name, int quantity, String note,
                       List<Ingredient> removedIngredients, List<Extra> extras, long unitGrossMinor,
                       int taxRateBps, long lineGrossMinor) {
    }

    public record Ingredient(UUID ingredientId, String name) {
    }

    public record Extra(UUID ingredientId, String name, int quantity) {
    }
}
