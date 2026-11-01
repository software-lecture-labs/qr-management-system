package com.restofy.kitchen.domain.exception;

import java.util.List;

/** The accepted-order message violates the contract; nothing was stored. */
public class InvalidOrderMessageException extends RuntimeException {

	private final List<String> violations;

	public InvalidOrderMessageException(List<String> violations) {
		super("Order accepted message is invalid: " + String.join("; ", violations));
		this.violations = List.copyOf(violations);
	}

	public List<String> getViolations() {
		return violations;
	}
}
