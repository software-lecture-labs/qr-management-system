package com.restofy.waiter.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Policy of the background reconciliation of table commands that have no final answer of QR. These are plain
 * properties with defaults in application.properties, not environment variables: how a lost answer is found
 * again is a design decision, not a per-environment setting. The reconciler only asks QR what it recorded for a
 * command id; it never sends a command again.
 *
 * @param enabled      switches the background trigger; the commands are recorded either way
 * @param intervalMs   pause between two rounds
 * @param batchSize    commands checked per round
 * @param minAgeSeconds how long a command must have been quiet before QR is asked about it (also the pause
 *                     between two checks of the same command); a command younger than the request timeouts is
 *                     still in flight in a request thread
 * @param maxAgeHours  commands older than this are no longer checked automatically; they stay visible and can
 *                     still be queried by hand
 */
@ConfigurationProperties("restofy.table-commands.reconciler")
public record TableCommandReconcilerProperties(boolean enabled, long intervalMs, int batchSize, long minAgeSeconds,
                                               long maxAgeHours) {

    /** Upper bound of {@code batch-size}: one round must stay short. */
    public static final int MAX_BATCH_SIZE = 200;

    public TableCommandReconcilerProperties {
        if (intervalMs <= 0) throw new IllegalArgumentException("interval-ms must be greater than zero");
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("batch-size must be between 1 and " + MAX_BATCH_SIZE);
        }
        if (minAgeSeconds < 1) throw new IllegalArgumentException("min-age-seconds must be greater than zero");
        if (maxAgeHours < 1) throw new IllegalArgumentException("max-age-hours must be greater than zero");
    }

    public Duration minAge() {
        return Duration.ofSeconds(minAgeSeconds);
    }

    public Duration maxAge() {
        return Duration.ofHours(maxAgeHours);
    }
}
