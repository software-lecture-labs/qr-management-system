package com.restofy.order.application.service;

import com.restofy.order.infrastructure.config.IdempotencyProperties;
import com.restofy.order.infrastructure.config.MessagingRetentionProperties;
import com.restofy.order.infrastructure.persistence.CartCleanupStore;
import com.restofy.order.infrastructure.persistence.IdempotencyStore;
import com.restofy.order.infrastructure.persistence.MessagingCleanupStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.function.IntUnaryOperator;

/**
 * Removes expired idempotency records, long-dead EXPIRED carts, old processed inbox events and fully delivered
 * outbox events, one bounded batch at a time. Every run is safe to
 * repeat and to run concurrently on several instances: batches only take rows they can lock, and a second run simply
 * finds nothing left. Expiry of an idempotency record only ends the "answer an exact retry" window; it never reopens a
 * consumed cart (see {@link IdempotencyProperties}).
 */
@Slf4j
@Service
public class DataRetentionService {

    /** Upper bound of batches per run so one run cannot monopolize the database after a long outage. */
    static final int MAX_BATCHES_PER_RUN = 100;

    private final IdempotencyStore idempotency;
    private final CartCleanupStore cartCleanup;
    private final IdempotencyProperties properties;
    private final MessagingCleanupStore messaging;
    private final MessagingRetentionProperties messagingProperties;
    private final Clock clock;

    public DataRetentionService(IdempotencyStore idempotency, CartCleanupStore cartCleanup,
                                IdempotencyProperties properties, MessagingCleanupStore messaging,
                                MessagingRetentionProperties messagingProperties, Clock clock) {
        this.idempotency = idempotency;
        this.cartCleanup = cartCleanup;
        this.properties = properties;
        this.messaging = messaging;
        this.messagingProperties = messagingProperties;
        this.clock = clock;
    }

    /** @return the number of deleted idempotency records */
    public int purgeExpiredIdempotencyRecords() {
        Instant now = clock.instant();
        int total = 0;
        for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
            int deleted = idempotency.purgeExpired(now, properties.cleanupBatchSize());
            total += deleted;
            if (deleted < properties.cleanupBatchSize()) {
                break;
            }
        }
        return total;
    }

    /** @return the number of deleted EXPIRED carts */
    public int purgeExpiredCarts() {
        Instant olderThan = clock.instant().minus(properties.expiredCartRetention());
        int total = 0;
        for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
            int deleted = cartCleanup.purgeExpiredCarts(olderThan, properties.cleanupBatchSize());
            total += deleted;
            if (deleted < properties.cleanupBatchSize()) {
                break;
            }
        }
        return total;
    }

    /**
     * Processed inbox events older than the deduplication window ({@code restofy.messaging.inbox-retention}, 90 days by
     * default). Deleting one only means a redelivery of that very old event would no longer be recognised, which is why
     * the window may not be configured short.
     *
     * @return the number of deleted inbox events
     */
    public int purgeProcessedInboxEvents() {
        Instant olderThan = clock.instant().minus(messagingProperties.inboxRetention());
        return inBatches(batchSize -> messaging.purgeInbox(olderThan, batchSize));
    }

    /**
     * Outbox events whose every delivery is confirmed DELIVERED longer ago than
     * {@code restofy.messaging.delivered-outbox-retention} (30 days by default). Undelivered or failed events are kept
     * for ever, so without a dispatcher nothing is removed.
     *
     * @return the number of deleted outbox events
     */
    public int purgeDeliveredOutboxEvents() {
        Instant olderThan = clock.instant().minus(messagingProperties.deliveredOutboxRetention());
        return inBatches(batchSize -> messaging.purgeDeliveredOutbox(olderThan, batchSize));
    }

    private int inBatches(IntUnaryOperator purgeOneBatch) {
        int total = 0;
        for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
            int deleted = purgeOneBatch.applyAsInt(properties.cleanupBatchSize());
            total += deleted;
            if (deleted < properties.cleanupBatchSize()) {
                break;
            }
        }
        return total;
    }

    public void runAll() {
        int records = purgeExpiredIdempotencyRecords();
        int carts = purgeExpiredCarts();
        int inbox = purgeProcessedInboxEvents();
        int outbox = purgeDeliveredOutboxEvents();
        if (records > 0 || carts > 0 || inbox > 0 || outbox > 0) {
            log.info("Retention run finished: idempotencyRecordsDeleted={} expiredCartsDeleted={} inboxEventsDeleted={}"
                    + " deliveredOutboxEventsDeleted={}", records, carts, inbox, outbox);
        }
    }
}
