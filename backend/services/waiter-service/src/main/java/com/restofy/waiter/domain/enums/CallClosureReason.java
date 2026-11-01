package com.restofy.waiter.domain.enums;

/**
 * Why a call ended without being resolved. Closing the table session ends its open calls, but that is not
 * "the work was done": such calls are CANCELLED with this reason and are never counted as resolved.
 */
public enum CallClosureReason {
    TABLE_SESSION_CLOSED
}
