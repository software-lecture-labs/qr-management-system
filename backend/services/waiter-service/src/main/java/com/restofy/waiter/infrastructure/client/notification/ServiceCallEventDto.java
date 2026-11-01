package com.restofy.waiter.infrastructure.client.notification;

import com.restofy.waiter.domain.entity.ServiceRequest;
import com.restofy.waiter.domain.enums.CallEventType;
import com.restofy.waiter.domain.enums.ServiceRequestStatus;
import com.restofy.waiter.domain.enums.ServiceRequestType;

import java.time.Instant;
import java.util.UUID;

/**
 * DRAFT Notification contract v0: a state snapshot of one call at one version. It carries the minimum
 * Notification needs: no guest token, no guest identity and no free text of the guest. Because it is a full
 * snapshot, the receiver applies it only if {@code call.version} is newer than what it holds, which makes
 * duplicates and out-of-order delivery harmless.
 */
public record ServiceCallEventDto(UUID eventId, int schemaVersion, String eventType, Instant occurredAt,
                                  UUID restaurantId, Call call) {

    public static final int SCHEMA_VERSION = 1;

    /**
     * {@code cancellationReason} and {@code cancelledAt} are set only for {@code SERVICE_CALL_CANCELLED}: the call
     * ended unresolved because its table session was closed. A cancelled call is not a resolved one.
     */
    public record Call(UUID id, UUID tableId, UUID tableSessionId, ServiceRequestType type, ServiceRequestStatus status,
                       long version, UUID claimedByUserId, Instant claimedAt, UUID resolvedByUserId, Instant resolvedAt,
                       String cancellationReason, Instant cancelledAt) {
    }

    public static ServiceCallEventDto of(UUID eventId, CallEventType type, Instant occurredAt, ServiceRequest call) {
        return new ServiceCallEventDto(eventId, SCHEMA_VERSION, type.wireName(), occurredAt, call.getRestaurantId(),
                new Call(call.getId(), call.getTableId(), call.getTableSessionId(), call.getRequestType(),
                        call.getStatus(), call.getVersion(), call.getClaimedByUserId(), call.getClaimedAt(),
                        call.getResolvedByUserId(), call.getResolvedAt(),
                        call.getClosureReason() == null ? null : call.getClosureReason().name(), call.getCancelledAt()));
    }
}
