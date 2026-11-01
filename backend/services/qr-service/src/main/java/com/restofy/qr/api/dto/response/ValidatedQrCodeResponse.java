package com.restofy.qr.api.dto.response;

import java.time.Instant;
import java.util.UUID;

/** A lookup snapshot, never a staff identity, guest session or authorization grant. */
public record ValidatedQrCodeResponse(UUID qrId, UUID restaurantId, UUID tableId,
                                       String tableNumber, String displayName, Instant checkedAt) {
}
