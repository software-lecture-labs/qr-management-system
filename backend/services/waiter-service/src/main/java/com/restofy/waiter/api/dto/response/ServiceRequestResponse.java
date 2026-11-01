package com.restofy.waiter.api.dto.response;

import com.restofy.waiter.domain.enums.ServiceRequestStatus;
import com.restofy.waiter.domain.enums.ServiceRequestType;

import java.time.Instant;
import java.util.UUID;

/**
 * An unresolved call of the table's live session. The guest identity is deliberately not exposed.
 * {@code version} is what a claim or resolve sends back as {@code expectedVersion}.
 */
public record ServiceRequestResponse(UUID id, ServiceRequestType type, ServiceRequestStatus status, String message,
                                     Instant createdAt, Instant claimedAt, long version) {
}
