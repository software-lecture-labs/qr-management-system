package com.restofy.waiter.api.dto.response;

import com.restofy.waiter.domain.enums.ServiceRequestStatus;
import com.restofy.waiter.domain.enums.ServiceRequestType;

import java.time.Instant;
import java.util.UUID;

/** What the guest gets back for its own call: no staff identities and no internal references. */
public record GuestCallResponse(UUID id, ServiceRequestType type, ServiceRequestStatus status, String message,
                                Instant createdAt) {
}
