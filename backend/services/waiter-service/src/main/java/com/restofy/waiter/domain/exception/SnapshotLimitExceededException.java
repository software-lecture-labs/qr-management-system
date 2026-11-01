package com.restofy.waiter.domain.exception;

/**
 * The restaurant has more tables than Waiter composes in one snapshot. Failing is safer than filtering or
 * counting a truncated table set.
 */
public class SnapshotLimitExceededException extends RuntimeException {

    public SnapshotLimitExceededException(String message) {
        super(message);
    }
}
