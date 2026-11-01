package com.restofy.kitchen.domain.enums;

/**
 * Why the kitchen asks for a whole order to be cancelled. Mandatory for every request (unlike the optional reason of a
 * product availability change). The names are the stored and published codes; their meaning is fixed.
 */
public enum CancellationReason {
	/** A needed product is out of stock. */
	OUT_OF_STOCK,
	/** The customer asked for it. */
	CUSTOMER_REQUEST,
	/** The kitchen made a mistake. */
	KITCHEN_ERROR,
	/** Any other reason. */
	OTHER
}
