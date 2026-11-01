package com.restofy.order.api.dto.response;

import java.util.UUID;

/**
 * What the Order Service made of the Kitchen's own cancellation request. The request being received never cancels by
 * itself: {@code outcome} APPLIED means the Order Service checked its rules and cancelled; REJECTED means it checked them
 * and did not ({@code rejectionCode}), and the refusal is recorded. {@code duplicate=true}: this event id was handled
 * before; {@code cancellationId} is then absent if the original record is no longer retrievable from the idempotency
 * window, but the outcome is the original one and nothing was done twice.
 */
public record KitchenCancellationRequestResponse(String outcome, boolean duplicate, UUID orderId, UUID cancellationId,
                                                 String scope, String rejectionCode, String fulfillmentStatus,
                                                 String paymentStatus) {
}
