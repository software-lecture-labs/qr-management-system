package com.restofy.order.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Versioned (schemaVersion 1) event announcing that the Payment Service reported a PAID that the Order Service could not
 * apply, because the order is cancelled or its payment was already cancelled. Money may really have been collected, so
 * this is a financial discrepancy for manual reconciliation and refund review, never an automatic refund. The order's
 * own statuses are unchanged. {@code reportedPaymentEventId} is the Payment Service's own event id; consumers must
 * deduplicate on {@code eventId}. No dispatcher delivers it yet (see the outbox).
 *
 * @param reason ORDER_CANCELLED, PAYMENT_CANCELLED, ATTEMPT_ENDED (the attempt had already failed), AMOUNT_MISMATCH,
 *               SECOND_CAPTURE (the order is already PAID by a different collection: a possible double charge) or
 *               CAPTURE_USED_ON_OTHER_ORDER (the same collection id already belongs to another order)
 */
public record OrderPaymentReconciliationRequiredEvent(UUID eventId, String eventType, int schemaVersion,
                                                      Instant occurredAt, String correlationId, UUID restaurantId,
                                                      UUID tableId, UUID tableSessionId, UUID orderId, String orderNo,
                                                      String reason, UUID reportedPaymentEventId,
                                                      UUID reportedPaymentAttemptId, UUID reportedCaptureId,
                                                      long reportedAmountMinor, String reportedCurrency,
                                                      Instant reportedOccurredAt, String fulfillmentStatus,
                                                      String paymentStatus, long dueMinor) {

    public static final String TYPE = "OrderPaymentReconciliationRequired";
    public static final int SCHEMA_VERSION = 1;
}
