package com.restofy.qr.domain.entity;

import com.restofy.qr.domain.enums.TableSessionStatus;
import com.restofy.qr.domain.IdleWindow;
import com.restofy.qr.domain.exception.TableSessionConflictException;
import com.restofy.qr.domain.exception.SessionClosureConflictException;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "table_sessions")
public class TableSession {
    @Id
    private UUID id;
    @Column(name = "restaurant_id", nullable = false, updatable = false)
    private UUID restaurantId;
    @Column(name = "table_id", nullable = false, updatable = false)
    private UUID tableId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TableSessionStatus status;
    @Column(name = "opened_at", nullable = false, updatable = false)
    private Instant openedAt;
    @Column(name = "last_activity_at", nullable = false)
    private Instant lastActivityAt;
    @Column(name = "idle_expires_at")
    private Instant idleExpiresAt;
    @Column(name = "closing_at")
    private Instant closingAt;
    @Column(name = "closed_at")
    private Instant closedAt;
    @Column(name = "closed_by_user_id")
    private UUID closedByUserId;
    @Version
    private long version;

    protected TableSession() {
        // JPA
    }

    public TableSession(UUID restaurantId, UUID tableId, Instant now) {
        this.id = UUID.randomUUID();
        this.restaurantId = Objects.requireNonNull(restaurantId);
        this.tableId = Objects.requireNonNull(tableId);
        this.openedAt = IdleWindow.storedTime(Objects.requireNonNull(now));
        this.lastActivityAt = openedAt;
        this.idleExpiresAt = IdleWindow.deadline(openedAt);
        this.status = TableSessionStatus.OPEN;
    }

    public boolean isOpenAt(Instant now) {
        return isOpenAt(now, false);
    }

    public boolean isOpenAt(Instant now, boolean orderProtected) {
        return status == TableSessionStatus.OPEN && closingAt == null && closedAt == null
                && !openedAt.isAfter(now) && !lastActivityAt.isAfter(now)
                && (orderProtected || idleExpiresAt == null || idleExpiresAt.isAfter(now));
    }

    public void recordActivityAt(Instant now) {
        recordActivityAt(now, false);
    }

    public void recordActivityAt(Instant now, boolean orderProtected) {
        if (!isOpenAt(now, orderProtected)) {
            throw new TableSessionConflictException();
        }
        lastActivityAt = IdleWindow.storedTime(now);
        idleExpiresAt = IdleWindow.deadline(lastActivityAt);
    }

    public boolean isIdleDueAt(Instant now) {
        return status == TableSessionStatus.OPEN && closingAt == null && closedAt == null
                && idleExpiresAt != null && !idleExpiresAt.isAfter(now);
    }

    public void restartIdleAfterCancellation(Instant cancelledAt, Instant now, boolean wasProtected) {
        if (!isOpenAt(now, wasProtected)) { return; }
        if (cancelledAt.isAfter(now) || cancelledAt.isBefore(openedAt)) { throw new TableSessionConflictException(); }
        idleExpiresAt = IdleWindow.deadline(cancelledAt.isAfter(lastActivityAt) ? cancelledAt : lastActivityAt);
    }

    // Service must first establish verified NO_ORDERS under the parent lock.
    public boolean expireIdleAt(Instant now) {
        if (!isIdleDueAt(now)) {
            return false;
        }
        status = TableSessionStatus.EXPIRED;
        closedAt = now;
        return true;
    }

    public void beginClosingAt(Instant now) {
        if ((status != TableSessionStatus.OPEN && status != TableSessionStatus.CLOSING)
                || closedAt != null || now.isBefore(lastActivityAt)
                || (closingAt != null && now.isBefore(closingAt))) { throw new SessionClosureConflictException(); }
        if (status == TableSessionStatus.OPEN) {
            closingAt = IdleWindow.storedTime(now);
            status = TableSessionStatus.CLOSING;
        }
    }

    public void closeAt(Instant now) {
        closeAt(now, null);
    }

    public void closeAt(Instant now, UUID verifiedStaffUserId) {
        if (status != TableSessionStatus.CLOSING || closingAt == null || closedAt != null
                || now.isBefore(lastActivityAt) || now.isBefore(closingAt)) { throw new SessionClosureConflictException(); }
        status = TableSessionStatus.CLOSED;
        closedAt = IdleWindow.storedTime(now);
        closedByUserId = verifiedStaffUserId; // Null for automatic closure; otherwise the verified immutable intent actor.
    }

    public void expireClosingAt(Instant now) {
        if (status != TableSessionStatus.CLOSING || closedAt != null || idleExpiresAt == null
                || idleExpiresAt.isAfter(now) || now.isBefore(lastActivityAt) || now.isBefore(closingAt)) {
            throw new SessionClosureConflictException();
        }
        status = TableSessionStatus.EXPIRED;
        closedAt = IdleWindow.storedTime(now);
    }

    /** Only called after a verified source abort or a committed guest-only idle transition. */
    public void reopenAfterTransition(Instant now) {
        if (status != TableSessionStatus.CLOSING || closedAt != null || now.isBefore(closingAt)
                || now.isBefore(lastActivityAt)) { throw new SessionClosureConflictException(); }
        status = TableSessionStatus.OPEN;
        closingAt = null;
    }

    public UUID getId() { return id; }
    public UUID getRestaurantId() { return restaurantId; }
    public UUID getTableId() { return tableId; }
    public TableSessionStatus getStatus() { return status; }
    public Instant getOpenedAt() { return openedAt; }
    public Instant getLastActivityAt() { return lastActivityAt; }
    public Instant getIdleExpiresAt() { return idleExpiresAt; }
    public Instant getClosingAt() { return closingAt; }
    public Instant getClosedAt() { return closedAt; }
    public UUID getClosedByUserId() { return closedByUserId; }
    public long getVersion() { return version; }
}
