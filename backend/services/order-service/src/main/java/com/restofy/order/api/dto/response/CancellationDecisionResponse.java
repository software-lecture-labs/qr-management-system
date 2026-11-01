package com.restofy.order.api.dto.response;

import java.util.UUID;

/**
 * Acknowledgement of the Kitchen's decision. {@code outcome}: APPLIED (the decision was recorded: the cancellation is
 * now {@code cancellationStatus}) or IGNORED_STALE (it had been decided before, nothing changed). {@code
 * cancellationStatus} says what really happened: a confirmation that arrived after the order moved on or was paid is
 * answered by the Order Service itself with REJECTED. {@code duplicate=true}: this event id was processed before.
 */
public record CancellationDecisionResponse(String outcome, boolean duplicate, UUID orderId, UUID cancellationId,
                                           String cancellationStatus, String fulfillmentStatus,
                                           String paymentStatus) {
}
