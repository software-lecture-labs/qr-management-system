package com.restofy.kitchen.domain.enums;

/** State of delivering one outbox event to one destination. DELIVERED and FAILED are final. */
public enum DeliveryStatus {
	/** Not yet delivered; will be (re)tried at {@code nextAttemptAt}. */
	PENDING,
	/** The destination acknowledged the event. */
	DELIVERED,
	/** Gave up (permanent failure or attempts exhausted); needs manual reconciliation. */
	FAILED
}
