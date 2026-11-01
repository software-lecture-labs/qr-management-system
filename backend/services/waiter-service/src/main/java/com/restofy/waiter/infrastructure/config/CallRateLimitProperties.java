package com.restofy.waiter.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * How many new calls one guest session may raise in a sliding window. The limit is counted from the calls
 * stored in the database, so it holds across several instances and needs no in-memory table that an attacker
 * could fill. A repeat of a finished request (same Idempotency-Key) and the return of an already active call
 * are not new calls and never count. These are plain properties with defaults in application.properties, not
 * environment variables: the limit protects the staff from a flood, it is not a per-environment setting.
 *
 * @param maxNewCalls   new calls allowed per guest session within the window
 * @param windowSeconds length of the sliding window
 */
@ConfigurationProperties("restofy.calls.guest-rate-limit")
public record CallRateLimitProperties(int maxNewCalls, long windowSeconds) {

    public CallRateLimitProperties {
        if (maxNewCalls < 1) throw new IllegalArgumentException("max-new-calls must be greater than zero");
        if (windowSeconds < 1) throw new IllegalArgumentException("window-seconds must be greater than zero");
    }

    public Duration window() {
        return Duration.ofSeconds(windowSeconds);
    }
}
