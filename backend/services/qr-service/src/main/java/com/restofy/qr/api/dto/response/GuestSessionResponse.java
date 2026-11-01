package com.restofy.qr.api.dto.response;

import com.restofy.qr.domain.enums.GuestSessionStatus;
import java.time.Instant;
import java.util.UUID;

public record GuestSessionResponse(UUID id, UUID restaurantId, UUID tableSessionId, GuestSessionStatus status,
                                   long version, Instant joinedAt, Instant lastActivityAt, Instant expiresAt, boolean orderProtected) {
}
