package com.restofy.order.domain.closure;

/** What the Order Service concludes about closing a table session. */
public enum ClosureOutcome {
    /** Every order of the session is finished and settled; the table may be closed. */
    ELIGIBLE,
    /** At least one condition is definitely not met. */
    NOT_ELIGIBLE,
    /** Nothing is definitely wrong, but something is not known well enough: the table is not closed (fail closed). */
    UNKNOWN
}
