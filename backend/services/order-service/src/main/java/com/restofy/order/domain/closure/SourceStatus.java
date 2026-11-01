package com.restofy.order.domain.closure;

/** The Order Service's standing on one lifecycle operation, as the QR Service reads it (QR's own vocabulary). */
public enum SourceStatus {
    /** The Order Service has never seen this operation. */
    NOT_STARTED,
    /** Not known well enough to decide; nothing is permitted and nothing is held. */
    UNKNOWN,
    /** Definitely not permitted. */
    BLOCKED,
    /** Permitted, and new orders are kept out as long as the operation is held. */
    AUTHORISED
}
