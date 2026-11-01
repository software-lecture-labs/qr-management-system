package com.restofy.order.domain.closure;

/** What the Order Service permits the QR Service to do to the table session (QR's own vocabulary). */
public enum ClosureEffect {
    /** Nothing is permitted. */
    NONE,
    /** Close the whole settled table session. */
    SETTLED_CLOSE,
    /** No order protects the session: the main session and every guest may expire idle. */
    IDLE_MAIN,
    /** Only the named guests may expire idle; orders protect the rest. */
    IDLE_GUESTS
}
