package com.restofy.qr.api.dto.response;

import com.restofy.qr.domain.enums.TableSessionStatus;
import java.time.Instant;
import java.util.UUID;

/** Main session metadata only; its UUID grants no guest or staff authority. */
public record TableSessionResponse(UUID id, UUID restaurantId, UUID tableId, TableSessionStatus status,
                                    long version, Instant openedAt, Instant lastActivityAt, Instant idleExpiresAt, boolean orderProtected) {
}
