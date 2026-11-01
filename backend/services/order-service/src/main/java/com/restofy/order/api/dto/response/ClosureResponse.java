package com.restofy.order.api.dto.response;

import com.restofy.order.domain.closure.ClosureBlocker;
import com.restofy.order.domain.closure.ClosureEffect;
import com.restofy.order.domain.closure.ClosureOutcome;
import com.restofy.order.domain.closure.ClosurePurpose;
import com.restofy.order.domain.closure.SourceStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The Order Service's answer about one lifecycle operation of a table session. It is the QR Service's "authority": read
 * {@code sourceStatus}, {@code effect}, {@code sourceRevision}, {@code verifiedAt}, {@code validUntil}, {@code complete},
 * {@code checkoutFenced} and {@code idleGuestIds} to decide whether to apply the change.
 *
 * <p>{@code sourceStatus}: {@code AUTHORISED} (permitted; {@code effect} says what, orders are kept out while the
 * operation is held), {@code BLOCKED} (definitely not), {@code UNKNOWN} (not known well enough; nothing held) or
 * {@code NOT_STARTED} (the Order Service never saw this operation). A permit is only usable until {@code validUntil};
 * asking again with the same operation renews it (and re-checks the orders), which is how a permit that was not applied
 * in time is refreshed or withdrawn. {@code state} is the operation as stored: {@code NOT_PREPARED} (nothing was held),
 * {@code PREPARED}, {@code COMPLETED}, {@code RELEASED} or {@code NOT_STARTED}. {@code outcome} and {@code blockers}
 * give the reason in the Order Service's terms and never carry money amounts or guest identity.
 * {@code scopeFingerprint} pins the facts a permit was decided on. {@code commandDigest} is the digest of the exact
 * command this answer is about, so the QR Service can check it is the answer to ITS command (null only for a
 * {@code NOT_STARTED} answer to a question that did not carry a digest).
 *
 * <p>{@code scopeDrift} is true only for a COMPLETED operation whose orders had moved since the preparation when the
 * QR Service confirmed CLOSED. The confirmation is always accepted (the change was already applied there), the
 * difference is recorded for people to reconcile, and {@code driftBlockers} says what was unmet or unknown at that moment.
 */
public record ClosureResponse(UUID operationId, UUID restaurantId, UUID tableSessionId, ClosurePurpose purpose,
                              String commandDigest, String state, SourceStatus sourceStatus, ClosureEffect effect, ClosureOutcome outcome,
                              List<ClosureBlocker> blockers, int orderCount, String scopeFingerprint,
                              long sourceRevision, Instant verifiedAt, Instant validUntil, boolean complete,
                              boolean checkoutFenced, List<UUID> idleGuestIds, Instant preparedAt,
                              Instant completedAt, Instant releasedAt, boolean scopeDrift,
                              List<ClosureBlocker> driftBlockers) {

    public ClosureResponse {
        blockers = ResponseCollections.copyOf(blockers);
        idleGuestIds = ResponseCollections.copyOf(idleGuestIds);
        driftBlockers = ResponseCollections.copyOf(driftBlockers);
    }
}
