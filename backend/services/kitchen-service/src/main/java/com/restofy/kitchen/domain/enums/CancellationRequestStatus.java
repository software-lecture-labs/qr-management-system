package com.restofy.kitchen.domain.enums;

/**
 * State of a cancellation REQUEST, not of the order. {@code REQUESTED} never means the order is cancelled: only an
 * {@code APPROVED} request, decided by the Order service, leads to a cancelled kitchen order. How the request travels
 * to the Order service is tracked by the outbox, not by this status.
 */
public enum CancellationRequestStatus {
	REQUESTED,
	APPROVED,
	REJECTED
}
