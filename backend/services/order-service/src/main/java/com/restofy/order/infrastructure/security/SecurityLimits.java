package com.restofy.order.infrastructure.security;

/**
 * Security limits shared by every authentication filter, so the same rule is never enforced with different numbers.
 * Existing policy, only named: nothing here is new or looser than before.
 */
final class SecurityLimits {

    /**
     * Longest guest token, staff credential or service credential accepted. Longer values are refused as invalid before
     * any lookup, so an oversized header can neither reach a backend port nor be used to probe it.
     */
    static final int MAX_CREDENTIAL_LENGTH = 512;

    private SecurityLimits() {
    }
}
