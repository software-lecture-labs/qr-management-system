package com.restofy.waiter.domain.enums;

/**
 * DRAFT Order contract: derived payment summary. Payment owns attempts and settlements; this value is a
 * display projection and never proof of payment.
 */
public enum PaymentSummaryStatus {
    NOT_PAID,
    PENDING,
    PAID,
    UNKNOWN
}
