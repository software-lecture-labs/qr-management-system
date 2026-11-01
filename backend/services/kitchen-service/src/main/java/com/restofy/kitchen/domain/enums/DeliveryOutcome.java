package com.restofy.kitchen.domain.enums;

/**
 * What one delivery attempt told us. An unknown or ambiguous result (timeout, connection error, 5xx) is never a
 * success and never a final failure: it is RETRYABLE_FAILURE and the same event id is sent again.
 */
public enum DeliveryOutcome {
	DELIVERED,
	RETRYABLE_FAILURE,
	PERMANENT_FAILURE
}
