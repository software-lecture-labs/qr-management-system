package com.restofy.order.application.service;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Payment references in the audit trail. Every applied payment change writes its references into the (append-only,
 * never cleaned) history row's reason, e.g. {@code attemptId=…;captureId=…;amountMinor=9000;currency=TRY}. The attempts an
 * order has seen are read back from it; collections themselves live in {@code payment_captures}.
 */
final class PaymentReferences {

    private static final Pattern ATTEMPT = Pattern.compile("attemptId=([0-9a-f-]{36})");

    private PaymentReferences() {
    }

    static String reason(UUID attemptId, UUID captureId, Long amountMinor, String currency) {
        StringBuilder reason = new StringBuilder("attemptId=").append(attemptId);
        if (captureId != null) {
            reason.append(";captureId=").append(captureId);
        }
        if (amountMinor != null) {
            reason.append(";amountMinor=").append(amountMinor).append(";currency=").append(currency);
        }
        return reason.toString();
    }

    /** The attempt a history reason refers to, or null for rows that carry none (for example the initial row). */
    static UUID attemptOf(String reason) {
        if (reason == null) {
            return null;
        }
        Matcher matcher = ATTEMPT.matcher(reason);
        return matcher.find() ? UUID.fromString(matcher.group(1)) : null;
    }
}
