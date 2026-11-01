package com.restofy.kitchen.domain.exception;

/**
 * The order already has a cancellation request that the Order service has not decided yet. A second request with
 * another Idempotency-Key is refused instead of creating a second request (and a second event) for the same order.
 */
public class CancellationAlreadyRequestedException extends RuntimeException {

	public CancellationAlreadyRequestedException() {
		super("A cancellation request for this order is already waiting for the Order service's decision");
	}
}
