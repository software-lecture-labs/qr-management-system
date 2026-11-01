package com.restofy.kitchen.domain.enums;

/** What is known about an availability command sent to the Menu service. */
public enum CommandOutcome {
	/** No reliable answer yet (not sent, timeout, error): never to be shown as done. May be repeated with the same id. */
	UNCONFIRMED,
	/** Menu confirmed and recorded the change. */
	CONFIRMED,
	/** Menu definitively rejected the command; a new command id is needed. */
	REJECTED
}
