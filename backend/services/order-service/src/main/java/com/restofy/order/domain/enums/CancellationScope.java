package com.restofy.order.domain.enums;

/** FULL cancels everything that is still active; PARTIAL leaves at least one unit active. */
public enum CancellationScope {
    FULL,
    PARTIAL
}
