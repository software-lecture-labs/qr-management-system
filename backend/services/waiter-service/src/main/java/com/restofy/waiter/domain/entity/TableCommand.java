package com.restofy.waiter.domain.entity;

import com.restofy.waiter.domain.enums.TableCommandStatus;
import com.restofy.waiter.domain.enums.TableCommandType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * Technical record of one command Waiter forwards to QR: closing a main table session ({@code CLOSE}) or
 * marking a table as cleaned ({@code CLEAN}). It is not the closure fact; QR owns that. The record exists so a
 * command whose answer was lost can be found again by its id and reconciled with QR, and so that every manual
 * action carries who asked, when, with which key and (for a close) the optional reason that was given.
 *
 * <p>The id doubles as the idempotency key towards QR, so every resend is recognisably the same command. A
 * close names the main session it targets, never only a table, so a command that arrives late cannot close a
 * newer session of the same table.
 */
@Entity
@Table(name = "table_commands")
@Getter
public class TableCommand {

    @Id
    private UUID id;

    @Column(name = "restaurant_id", nullable = false)
    private UUID restaurantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "command_type", nullable = false)
    private TableCommandType type;

    @Column(name = "table_id", nullable = false)
    private UUID tableId;

    /** Target main session of a close; null for cleaning. */
    @Column(name = "table_session_id")
    private UUID tableSessionId;

    /** The QR version the staff member saw: the session's for a close, the table's for cleaning. */
    @Column(name = "expected_version", nullable = false)
    private long expectedVersion;

    @Column(name = "requested_by_user_id", nullable = false)
    private UUID requestedByUserId;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "request_digest", nullable = false, length = 64)
    private String requestDigest;

    @Column(name = "reason_text")
    private String reasonText;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private TableCommandStatus status;

    @Column(name = "rejection_code")
    private String rejectionCode;

    /** Comma separated blocker codes QR named when it refused a close; only codes of a fixed set are kept. */
    @Column(name = "rejection_detail")
    private String rejectionDetail;

    @Column(name = "qr_closed_at")
    private Instant qrClosedAt;

    @Column(name = "ended_guest_sessions")
    private Integer endedGuestSessions;

    @Column(name = "cancelled_call_count")
    private Integer cancelledCallCount;

    /**
     * QR confirmed the close but named {@code RECONCILIATION_REQUIRED}: Order found a scope drift after the local
     * closure. The closure stands; this is an operational warning QR and Order reconcile, never a reason to reopen.
     */
    @Column(name = "reconciliation_required", nullable = false)
    private boolean reconciliationRequired;

    @Column(name = "table_version_after")
    private Long tableVersionAfter;

    /** Whether QR's table snapshot, taken when it confirmed the cleaning, showed the table free for a new customer. */
    @Column(name = "ready_for_new_customer")
    private Boolean readyForNewCustomer;

    /** Wrapper type on purpose: null marks a new entity, so Spring Data persists instead of merging. */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected TableCommand() {
    }

    /** Identity and audit data shared by both command kinds. */
    public record Origin(UUID id, UUID restaurantId, UUID tableId, long expectedVersion, UUID requestedByUserId,
                         String idempotencyKey, String requestDigest, Instant now) {
    }

    public static TableCommand close(Origin origin, UUID tableSessionId, String reason) {
        TableCommand command = open(origin, TableCommandType.CLOSE);
        command.tableSessionId = tableSessionId;
        command.reasonText = reason;
        return command;
    }

    public static TableCommand clean(Origin origin) {
        return open(origin, TableCommandType.CLEAN);
    }

    private static TableCommand open(Origin origin, TableCommandType type) {
        TableCommand command = new TableCommand();
        command.id = origin.id();
        command.restaurantId = origin.restaurantId();
        command.type = type;
        command.tableId = origin.tableId();
        command.expectedVersion = origin.expectedVersion();
        command.requestedByUserId = origin.requestedByUserId();
        command.idempotencyKey = origin.idempotencyKey();
        command.requestDigest = origin.requestDigest();
        command.status = TableCommandStatus.PENDING;
        command.createdAt = origin.now();
        command.updatedAt = origin.now();
        return command;
    }

    /** Marks a new attempt in flight; the version column makes two concurrent attempts collide. */
    public void markInFlight(Instant now) {
        requireOpen();
        status = TableCommandStatus.PENDING;
        updatedAt = now;
    }

    public void markUnknown(Instant now) {
        requireOpen();
        status = TableCommandStatus.UNKNOWN;
        updatedAt = now;
    }

    /** QR accepted the close but has not ended the main session and every guest session yet: not a success. */
    public void markInProgress(Instant now) {
        requireOpen();
        status = TableCommandStatus.IN_PROGRESS;
        updatedAt = now;
    }

    /** Records that QR was asked and had nothing new, so the next automatic check waits for a full interval. */
    public void touch(Instant now) {
        requireOpen();
        updatedAt = now;
    }

    /** QR confirmed that the main session and all its guest sessions ended; the calls of the session ended with it. */
    public void completeClose(Instant closedAt, int endedGuests, int cancelledCalls, boolean reconciliation, Instant now) {
        requireOpen();
        status = TableCommandStatus.COMPLETED;
        qrClosedAt = closedAt;
        endedGuestSessions = endedGuests;
        cancelledCallCount = cancelledCalls;
        reconciliationRequired = reconciliation;
        updatedAt = now;
        completedAt = now;
    }

    /**
     * QR confirmed the cleaning. {@code ready} is true only when QR's table snapshot showed the table available,
     * active and without a live session; a later reconciliation may find the table in use again already.
     */
    public void completeClean(long tableVersion, boolean ready, Instant now) {
        requireOpen();
        status = TableCommandStatus.COMPLETED;
        tableVersionAfter = tableVersion;
        readyForNewCustomer = ready;
        updatedAt = now;
        completedAt = now;
    }

    public void reject(String code, String detail, Instant now) {
        requireOpen();
        status = TableCommandStatus.REJECTED;
        rejectionCode = code;
        rejectionDetail = detail;
        updatedAt = now;
        completedAt = now;
    }

    /** No final answer of QR yet. */
    public boolean isOpen() {
        return !status.isFinal();
    }

    /** An answer of QR is final: a command can never turn from completed to rejected or back. */
    private void requireOpen() {
        if (status != null && status.isFinal()) {
            throw new IllegalStateException("The table command already has QR's answer");
        }
    }
}
