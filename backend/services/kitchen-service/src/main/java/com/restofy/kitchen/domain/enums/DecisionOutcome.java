package com.restofy.kitchen.domain.enums;

/**
 * What Kitchen did with one Order decision. The first five outcomes are RECORDED (an inbox row), so the same event can
 * never be applied twice; the last three are refusals that change and record nothing. Only {@link #CANCELLED} cancels a
 * kitchen order.
 */
public enum DecisionOutcome {
	/** APPROVED: the order moved to CANCELLED and the request to APPROVED, together. */
	CANCELLED,
	/** REJECTED: only the request became REJECTED; the order is exactly as it was. */
	REQUEST_REJECTED,
	/** The decision is not newer than what Kitchen already knows about the order: it is old or a repeat in disguise. */
	STALE,
	/** The request already has a decision (another event decided it first). */
	ALREADY_DECIDED,
	/** APPROVED, but the order can no longer be cancelled by Kitchen (e.g. it became READY): nothing is forced. */
	NEEDS_RECONCILIATION,

	/** Not a decision Kitchen knows: unknown order or request, another restaurant, a request of another order. */
	UNKNOWN,
	/** Malformed, unsupported or implausible content (schema, type, reason code, version, time). */
	INVALID,
	/** The sender is not the verified Order service. */
	SOURCE_NOT_TRUSTED;

	/** Is this outcome stored in the inbox (and therefore deduplicated)? */
	public boolean isRecorded() {
		return ordinal() <= NEEDS_RECONCILIATION.ordinal();
	}
}
