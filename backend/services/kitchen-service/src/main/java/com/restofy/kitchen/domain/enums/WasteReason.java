package com.restofy.kitchen.domain.enums;

/** Why food was thrown away. The names are the stored and published codes; their meaning is fixed. */
public enum WasteReason {
	/** Prepared wrongly. */
	WRONG_PREPARATION,
	/** Spoiled. */
	SPOILAGE,
	/** Dropped or damaged. */
	DROPPED_OR_DAMAGED,
	/** Wasted after an order was cancelled. */
	WASTE_AFTER_CANCELLATION,
	/** Any other reason. */
	OTHER
}
