package com.restofy.kitchen.domain.enums;

/** What the Order service decided about a cancellation request: the only fact that can cancel a kitchen order. */
public enum OrderDecision {
	/** The Order service cancelled the order. */
	APPROVED,
	/** The Order service refused; the order and its preparation are untouched. */
	REJECTED
}
