package com.restofy.waiter.infrastructure.client.qr;

import java.time.Instant;
import java.util.UUID;

/**
 * DRAFT QR contract v0.4: "this table is cleaned, make it available". {@code expectedVersion} is the table
 * version the staff member saw. QR refuses the command while the table still has a live (open or closing) main
 * session, whatever the staff member saw.
 */
public record QrCleanRequestDto(UUID commandId, UUID restaurantId, UUID tableId, long expectedVersion,
                                UUID requestedByUserId, Instant requestedAt) {
}
