package com.restofy.waiter.api.dto.response;

import com.restofy.waiter.domain.enums.NotificationDeliveryStatus;
import com.restofy.waiter.infrastructure.persistence.repository.NotificationDeliveries;

import java.time.Instant;
import java.util.UUID;

/**
 * Synchronization of the call's latest state with Notification, kept apart from the call's own status: a
 * RESOLVED call with {@code PENDING} or {@code DEAD} here is resolved for the restaurant but not yet known to
 * Notification. Only {@code DELIVERED} means Notification has the current version.
 */
public record NotificationSyncResponse(UUID eventId, long callVersion, NotificationDeliveryStatus status,
                                       int attemptCount, String lastErrorCode, Instant lastAttemptAt,
                                       Instant deliveredAt) {

    public static NotificationSyncResponse from(NotificationDeliveries.Sync sync) {
        return sync == null ? null : new NotificationSyncResponse(sync.eventId(), sync.callVersion(), sync.status(),
                sync.attemptCount(), sync.lastErrorCode(), sync.lastAttemptAt(), sync.deliveredAt());
    }
}
