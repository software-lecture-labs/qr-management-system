package com.restofy.order.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * How long processed inbox events and delivered outbox events are kept. Plain application properties
 * ({@code restofy.messaging.*}), not ENV keys; the cleanup cadence and batch size are shared with
 * {@link IdempotencyProperties}.
 *
 * <p>The inbox keeps an event id so that a redelivery is recognised, so its retention is also the deduplication window
 * and must be longer than any sender can plausibly retry: it may not be configured below {@link #MIN_INBOX_RETENTION}.
 * An outbox event is only ever removed after EVERY one of its deliveries is confirmed DELIVERED and the retention has
 * passed since the last of them; pending or failed deliveries are never removed, whatever their age. (There is no
 * dispatcher yet, so today nothing is DELIVERED and nothing is removed.)
 *
 * @param inboxRetention           keep processed inbox events this long (default 90 days)
 * @param deliveredOutboxRetention keep fully delivered outbox events this long (default 30 days)
 */
@ConfigurationProperties("restofy.messaging")
public record MessagingRetentionProperties(@DefaultValue("P90D") Duration inboxRetention,
                                           @DefaultValue("P30D") Duration deliveredOutboxRetention) {

    public static final Duration MIN_INBOX_RETENTION = Duration.ofDays(7);
    public static final Duration MIN_OUTBOX_RETENTION = Duration.ofDays(1);

    public MessagingRetentionProperties {
        if (inboxRetention == null || inboxRetention.compareTo(MIN_INBOX_RETENTION) < 0) {
            throw new IllegalArgumentException("restofy.messaging.inbox-retention must be at least "
                    + MIN_INBOX_RETENTION + ": it is the window in which a redelivered event is still recognised");
        }
        if (deliveredOutboxRetention == null || deliveredOutboxRetention.compareTo(MIN_OUTBOX_RETENTION) < 0) {
            throw new IllegalArgumentException("restofy.messaging.delivered-outbox-retention must be at least "
                    + MIN_OUTBOX_RETENTION);
        }
    }
}
