package com.restofy.order.api.dto.response;

import java.util.UUID;

/**
 * Acknowledgement of an internal event. {@code outcome}: APPLIED (the order moved), IGNORED_STALE (late or repeated,
 * nothing to do) or IGNORED_ORDER_CLOSED (the order is cancelled). {@code duplicate=true} means this exact event id
 * was processed before and {@code outcome} is the original result. {@code reconciliationRequired=true} means a PAID
 * event could not be applied (the order is cancelled, or its payment was already cancelled): it was NOT lost, it is
 * recorded and announced for manual reconciliation and refund review, but the order's statuses did not change. Both
 * independent statuses are reported as they are now.
 */
public record OrderEventResponse(String outcome, boolean duplicate, UUID orderId, String fulfillmentStatus,
                                 String paymentStatus, boolean reconciliationRequired) {
}
