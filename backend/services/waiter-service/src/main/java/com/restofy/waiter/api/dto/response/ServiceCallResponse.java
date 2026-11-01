package com.restofy.waiter.api.dto.response;

import com.restofy.waiter.domain.enums.ServiceRequestStatus;
import com.restofy.waiter.domain.enums.ServiceRequestType;

import java.time.Instant;
import java.util.UUID;

/**
 * A call as the waiter panel sees it. The guest session id is deliberately not exposed; the table and table
 * session are, so the panel can group and filter. {@code message} is the guest's free text and must be shown as
 * escaped text. {@code version} is the value to send back as {@code expectedVersion}. A call that ended
 * unresolved because its table session was closed is CANCELLED with {@code closureReason}; it was not resolved.
 */
public record ServiceCallResponse(UUID id, UUID tableId, UUID tableSessionId, ServiceRequestType type,
                                  ServiceRequestStatus status, String message, Instant createdAt,
                                  UUID claimedByUserId, Instant claimedAt, UUID resolvedByUserId, Instant resolvedAt,
                                  long version, String closureReason, Instant cancelledAt,
                                  NotificationSyncResponse notification) {
}
