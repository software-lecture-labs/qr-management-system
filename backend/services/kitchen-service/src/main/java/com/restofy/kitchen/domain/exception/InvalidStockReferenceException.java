package com.restofy.kitchen.domain.exception;

/**
 * The stock reference of a cancellation request does not fit: a stock (OUT_OF_STOCK) request without a product, a product
 * or line that is not part of THIS order, or a reference given with another reason. The message is deliberately generic
 * and the same whether the product exists elsewhere or not, so it cannot be used to probe other orders or restaurants.
 */
public class InvalidStockReferenceException extends RuntimeException {

	public InvalidStockReferenceException(String message) {
		super(message);
	}
}
