package com.restofy.waiter.domain.exception;

/** Why a downstream service could not provide an answer. Each value maps to its own API error. */
public enum DependencyFailure {
    /** Connection refused, reset, or the service answered with 5xx. */
    UNAVAILABLE,
    /** Connect or read timeout. */
    TIMEOUT,
    /** The service rejected Waiter itself (401/403): missing service identity, not a user problem. */
    REJECTED,
    /** Unexpected status, malformed or contract-violating body, or data of another tenant. */
    BAD_RESPONSE
}
