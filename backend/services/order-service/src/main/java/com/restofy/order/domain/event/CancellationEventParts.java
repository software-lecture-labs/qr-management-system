package com.restofy.order.domain.event;

import java.util.UUID;

/**
 * The building blocks shared by the cancellation events. Events carry internal ids, statuses, quantities and amounts,
 * never a token, a guest session id or a staff user id: who acted is told by kind and role only. The reason text is
 * customer-written plain text and must be treated as untrusted by every consumer.
 */
public final class CancellationEventParts {

    private CancellationEventParts() {
    }

    /** A cancelled line: how many units, how many are still active, and what the cancelled part was worth. */
    public record Item(UUID orderItemId, UUID productId, String name, int quantity, int remainingQuantity,
                       long amountMinor) {
    }

    /** One reason: a catalog code and/or free text, and who gave it (GUEST or STAFF). */
    public record Reason(String source, String code, String text) {
    }

    /** Who acted: GUEST, STAFF, SERVICE or SYSTEM, and the role (for example WAITER, ADMIN, KITCHEN). */
    public record Actor(String type, String role) {
    }
}
