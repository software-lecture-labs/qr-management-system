package com.restofy.kitchen.domain.exception;

/**
 * No kitchen order with this id exists for the caller's restaurant. Deliberately identical for "does not exist" and
 * "belongs to another restaurant" so the response cannot be used to probe other tenants.
 */
public class KitchenOrderNotFoundException extends RuntimeException {

	public KitchenOrderNotFoundException() {
		super("Kitchen order not found");
	}
}
