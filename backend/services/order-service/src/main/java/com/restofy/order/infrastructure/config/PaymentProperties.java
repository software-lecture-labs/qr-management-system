package com.restofy.order.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Settings for payment events from the Payment Service. A plain application property ({@code restofy.payment.*}), not an
 * ENV key.
 *
 * @param maxClockSkew how far ahead of this server's clock (UTC) an event may claim to have happened, to tolerate small
 *                     clock differences between services. A timestamp further in the future is refused (422) rather
 *                     than silently dropped, because it would otherwise freeze the ordering of every later event.
 *                     Events from the past are never refused for being old: ordering is decided by the state machine.
 */
@ConfigurationProperties("restofy.payment")
public record PaymentProperties(@DefaultValue("PT5M") Duration maxClockSkew) {

    static final Duration MAX_ALLOWED_SKEW = Duration.ofHours(1);

    public PaymentProperties {
        if (maxClockSkew == null || maxClockSkew.isNegative() || maxClockSkew.isZero()
                || maxClockSkew.compareTo(MAX_ALLOWED_SKEW) > 0) {
            throw new IllegalArgumentException("restofy.payment.max-clock-skew must be a positive duration of at most "
                    + MAX_ALLOWED_SKEW);
        }
    }
}
