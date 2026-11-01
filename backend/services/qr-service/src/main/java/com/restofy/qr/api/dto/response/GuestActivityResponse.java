package com.restofy.qr.api.dto.response;

import java.time.Instant;
import java.util.UUID;

public record GuestActivityResponse(UUID restaurantId, UUID tableSessionId, UUID guestSessionId,
        long mainVersion, long guestVersion, Instant lastActivityAt, Instant mainIdleExpiresAt, Instant guestExpiresAt,
        boolean mainOrderProtected, boolean guestOrderProtected) {
}
