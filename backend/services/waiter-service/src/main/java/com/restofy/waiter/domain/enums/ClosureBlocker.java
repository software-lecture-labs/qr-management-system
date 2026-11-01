package com.restofy.waiter.domain.enums;

import java.util.Optional;

/**
 * A reason QR may name in {@code blockers} when it refuses a close. Waiter assesses none of these itself: whether a
 * table session may close is Order's assessment for QR (ADR-QR-001 K7), and Waiter only passes on what QR said.
 * The set is closed: a code that is not in it is never passed on, so foreign text cannot reach the panel. QR's
 * implemented contract (K11) returns an empty list today; the set is Waiter's DRAFT vocabulary until QR and Order
 * agree on one.
 *
 * <p>There is deliberately no "force" or "ignore balance" blocker override anywhere: a manual close is the same
 * command, checked by the same owners, with an optional reason and the audit data of the staff member.
 */
public enum ClosureBlocker {

    /** An order is neither served nor cancelled and kitchen has not marked it ready (prepayment does not matter). */
    ORDER_IN_PREPARATION,
    /** An order is ready but not served yet (prepayment does not matter). */
    ORDER_AWAITING_SERVICE,
    /** A balance is still due. */
    UNPAID_BALANCE,
    /** A payment attempt is still running. */
    PAYMENT_IN_PROGRESS,
    /** A payment attempt ended without a certain result (timeout, lost callback). */
    PAYMENT_OUTCOME_UNKNOWN,
    /** A cancellation requires a refund that has not been confirmed as made. */
    REFUND_PENDING,
    /** The payment facts are missing or contradict each other: nothing can be verified. */
    SETTLEMENT_UNVERIFIED,
    /** An order acceptance is reserved or in flight for this session and not yet settled. */
    ORDER_ADMISSION_IN_FLIGHT;

    public static Optional<ClosureBlocker> parse(String code) {
        if (code == null) return Optional.empty();
        for (ClosureBlocker blocker : values()) {
            if (blocker.name().equals(code)) return Optional.of(blocker);
        }
        return Optional.empty();
    }
}
