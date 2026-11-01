package com.restofy.kitchen.domain.exception;

/** The Idempotency-Key was already used for a different order, command or actor; nothing was changed. */
public class CommandIdConflictException extends RuntimeException {

	public CommandIdConflictException() {
		super("The Idempotency-Key was already used for a different command");
	}
}
