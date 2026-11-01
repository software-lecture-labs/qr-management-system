package com.restofy.order.domain.enums;

/**
 * The cart lifecycle. A cart starts {@link #ACTIVE}, is the only status that can be read for editing or ordering, and
 * leaves it exactly once and for good:
 * <pre>
 *   ACTIVE &gt; CHECKED_OUT   the cart became an order
 *   ACTIVE &gt; EXPIRED       the cart was abandoned (no code expires carts yet; cleanup deletes EXPIRED ones)
 * </pre>
 * {@code CHECKED_OUT} and {@code EXPIRED} are final: nothing moves between them or back to ACTIVE. This is the only
 * place that defines the rule; {@code Cart} applies it to every status change.
 */
public enum CartStatus {
    ACTIVE,
    CHECKED_OUT,
    EXPIRED;

    public boolean canTransitionTo(CartStatus target) {
        return this == ACTIVE && (target == CHECKED_OUT || target == EXPIRED);
    }

    /** Only an ACTIVE cart may be changed; the other statuses are final. */
    public boolean isModifiable() {
        return this == ACTIVE;
    }
}
