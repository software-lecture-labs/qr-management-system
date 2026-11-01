package com.restofy.waiter.infrastructure.client.qr;

import java.time.Instant;
import java.util.UUID;

/**
 * QR's {@code StaffCloseRequest} (ADR-QR-001 K11): "close this main table session". {@code commandId} is fixed for
 * the life of the command and equals the Idempotency-Key; every resend carries exactly the same fields. The command
 * names the session it targets (and the session version the staff member saw), never only the table, so a late
 * command cannot close a newer session of the same table. {@code restaurantId} and {@code requestedByUserId} are
 * expectations QR checks against its verified service identity and staff delegation, never proof of either. Neither
 * a payment claim, a balance, a role nor a force flag is part of the command: QR asks Order, and QR rejects unknown
 * fields.
 */
public record QrCloseRequestDto(UUID commandId, UUID restaurantId, UUID tableId, UUID tableSessionId,
                                long expectedVersion, UUID requestedByUserId, String reason, Instant requestedAt) {
}
