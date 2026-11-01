package com.restofy.waiter.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Retry policy of the Notification delivery queue. These are plain properties with defaults in
 * application.properties, not environment variables: the delivery guarantees are a design decision, not a
 * per-environment setting.
 *
 * @param enabled        switches the background trigger; the queue itself is always written
 * @param intervalMs     pause between two dispatch rounds
 * @param batchSize      events leased per round
 * @param leaseSeconds   how long a leased event is hidden; after a crash it becomes due again
 * @param maxAttempts    attempts before an event is parked as DEAD
 * @param initialBackoffSeconds delay after the first failure; doubles on every failure
 * @param maxBackoffSeconds     upper bound of the delay
 */
@ConfigurationProperties("restofy.notification.dispatcher")
public record NotificationDispatcherProperties(boolean enabled, long intervalMs, int batchSize, long leaseSeconds,
                                               int maxAttempts, long initialBackoffSeconds, long maxBackoffSeconds) {

    /** Upper bound of {@code batch-size}: one round must stay a short database transaction. */
    public static final int MAX_BATCH_SIZE = 200;

    public NotificationDispatcherProperties {
        if (intervalMs <= 0) throw new IllegalArgumentException("interval-ms must be greater than zero");
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("batch-size must be between 1 and " + MAX_BATCH_SIZE);
        }
        if (leaseSeconds < 1) throw new IllegalArgumentException("lease-seconds must be greater than zero");
        if (maxAttempts < 1) throw new IllegalArgumentException("max-attempts must be greater than zero");
        if (initialBackoffSeconds < 1) {
            throw new IllegalArgumentException("initial-backoff-seconds must be greater than zero");
        }
        if (maxBackoffSeconds < initialBackoffSeconds) {
            throw new IllegalArgumentException("max-backoff-seconds must not be smaller than initial-backoff-seconds");
        }
    }

    public Duration lease() {
        return Duration.ofSeconds(leaseSeconds);
    }

    /** Delay after the given (1-based) failed attempt: exponential, bounded by the maximum. */
    public Duration backoffAfter(int attempt) {
        long seconds = initialBackoffSeconds;
        for (int step = 1; step < attempt && seconds < maxBackoffSeconds; step++) {
            seconds = Math.min(maxBackoffSeconds, seconds * 2);
        }
        return Duration.ofSeconds(Math.min(seconds, maxBackoffSeconds));
    }
}
