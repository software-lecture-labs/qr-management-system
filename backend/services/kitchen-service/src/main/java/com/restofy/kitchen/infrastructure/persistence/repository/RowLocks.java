package com.restofy.kitchen.infrastructure.persistence.repository;

/**
 * Named settings for the pessimistic row locks (SELECT ... FOR UPDATE) the repositories take. They are Strings because
 * annotation attributes must be compile-time constants. Kitchen takes row locks only on write paths; reads never lock.
 */
public final class RowLocks {

	/** JPA hint that bounds how long a lock may be waited for. */
	public static final String TIMEOUT_HINT = "jakarta.persistence.lock.timeout";
	/** Wait at most five seconds; a timeout surfaces as a locking failure (409 / retryable), never a hang. */
	public static final String TIMEOUT_MILLIS = "5000";

	private RowLocks() {
	}
}
