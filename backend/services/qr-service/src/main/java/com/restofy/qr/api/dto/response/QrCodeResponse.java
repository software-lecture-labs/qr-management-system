package com.restofy.qr.api.dto.response;

import com.restofy.qr.domain.enums.QrCodeStatus;
import java.time.Instant;
import java.util.UUID;

public record QrCodeResponse(UUID id, UUID restaurantId, UUID tableId, QrCodeStatus status,
                             long version, Instant issuedAt, Instant revokedAt, Instant expiresAt) {
}
