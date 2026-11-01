package com.restofy.waiter.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("restofy.rest-client")
public record RestClientProperties(int connectTimeoutMs, int readTimeoutMs) {

    /** Slack added to the client timeouts before a command without an answer is no longer considered running. */
    private static final Duration IN_FLIGHT_GRACE = Duration.ofSeconds(2);

    public RestClientProperties {
        if (connectTimeoutMs <= 0) throw new IllegalArgumentException("connect-timeout-ms must be greater than zero");
        if (readTimeoutMs <= 0) throw new IllegalArgumentException("read-timeout-ms must be greater than zero");
    }

    /** How long one downstream attempt can legitimately still be running: both timeouts plus a short grace. */
    public Duration inFlightWindow() {
        return Duration.ofMillis((long) connectTimeoutMs + readTimeoutMs).plus(IN_FLIGHT_GRACE);
    }
}
