package com.restofy.order.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Settings for cancellation requests. A plain application property ({@code restofy.cancellation.*}), not an ENV key.
 *
 * @param pendingAttentionAfter how long a cancellation request may wait for the Kitchen's answer before the administrator's
 *                              report marks it as overdue. It only makes a waiting request VISIBLE: nothing is approved,
 *                              rejected, escalated or sent because time passed (that needs the Kitchen's answer, and a
 *                              notification or escalation contract that does not exist yet).
 */
@ConfigurationProperties("restofy.cancellation")
public record CancellationProperties(@DefaultValue("PT15M") Duration pendingAttentionAfter) {

    static final Duration MAX_ALLOWED = Duration.ofDays(30);

    public CancellationProperties {
        if (pendingAttentionAfter == null || pendingAttentionAfter.isNegative() || pendingAttentionAfter.isZero()
                || pendingAttentionAfter.compareTo(MAX_ALLOWED) > 0) {
            throw new IllegalArgumentException("restofy.cancellation.pending-attention-after must be a positive duration"
                    + " of at most " + MAX_ALLOWED);
        }
    }
}
