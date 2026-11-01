package com.restofy.order.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Versioned (schemaVersion 1) event announcing that the payment status of an order changed. It carries ids, statuses and
 * the amounts, never card or provider data. {@code amountMinor} is the amount the Payment Service reported (and the Order
 * Service verified against what the order owed) for PENDING and PAID, otherwise null; {@code dueMinor} is the remaining
 * debt after the change (0 once PAID). {@code paymentAttemptId} is the Payment Service's attempt, {@code captureId}
 * (PAID only) its real collection. {@code fulfillmentStatus} lets a consumer such as QR judge whether the
 * table could be closed; the Order Service itself never closes a table. Consumers must deduplicate on {@code eventId}.
 * No dispatcher delivers it yet (see the outbox).
 */
public record OrderPaymentStatusChangedEvent(UUID eventId, String eventType, int schemaVersion, Instant occurredAt,
                                             String correlationId, UUID restaurantId, UUID tableId,
                                             UUID tableSessionId, UUID orderId, String orderNo, String fromStatus,
                                             String toStatus, String fulfillmentStatus, String currency,
                                             Long amountMinor, long dueMinor, UUID paymentAttemptId, UUID captureId,
                                             String source) {

    public static final String TYPE = "OrderPaymentStatusChanged";
    public static final int SCHEMA_VERSION = 1;
}
