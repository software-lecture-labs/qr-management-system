package com.restofy.order.domain.closure;

/** Why the QR Service asks: to close a settled table session, or to expire an idle one (QR's own vocabulary). */
public enum ClosurePurpose {
    SETTLED,
    IDLE
}
