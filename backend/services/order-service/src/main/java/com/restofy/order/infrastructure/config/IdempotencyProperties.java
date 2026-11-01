package com.restofy.order.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Retention and housekeeping of idempotency records and old carts. These are plain application properties
 * ({@code restofy.idempotency.*}), not ENV keys. Defaults are the initial policy: 24h for cart additions, 72h for
 * checkout and cancellation.
 *
 * <p>A record's retention only bounds how long an exact retry is answered with the previous result. It is never what
 * prevents a second order from the same cart: that is guaranteed by the database ({@code UNIQUE(restaurant_id,
 * cart_id)} and the terminal cart status).
 */
@ConfigurationProperties("restofy.idempotency")
public record IdempotencyProperties(
        @DefaultValue("PT24H") Duration cartItemRetention,
        @DefaultValue("PT72H") Duration checkoutRetention,
        @DefaultValue("PT72H") Duration cancellationRetention,
        @DefaultValue("PT1H") Duration cleanupInterval,
        @DefaultValue("PT5M") Duration cleanupInitialDelay,
        @DefaultValue("500") int cleanupBatchSize,
        @DefaultValue("P30D") Duration expiredCartRetention) {

    static final int MAX_CLEANUP_BATCH_SIZE = 10_000;

    public IdempotencyProperties {
        requirePositive(cartItemRetention, "cart-item-retention");
        requirePositive(checkoutRetention, "checkout-retention");
        requirePositive(cancellationRetention, "cancellation-retention");
        requirePositive(cleanupInterval, "cleanup-interval");
        requirePositive(cleanupInitialDelay, "cleanup-initial-delay");
        requirePositive(expiredCartRetention, "expired-cart-retention");
        if (cleanupBatchSize < 1 || cleanupBatchSize > MAX_CLEANUP_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    "restofy.idempotency.cleanup-batch-size must be within 1.." + MAX_CLEANUP_BATCH_SIZE);
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("restofy.idempotency." + name + " must be a positive duration");
        }
    }
}
