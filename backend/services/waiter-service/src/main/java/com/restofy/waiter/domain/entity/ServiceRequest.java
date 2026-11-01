package com.restofy.waiter.domain.entity;

import com.restofy.waiter.domain.enums.CallClosureReason;
import com.restofy.waiter.domain.enums.ServiceRequestStatus;
import com.restofy.waiter.domain.enums.ServiceRequestType;
import com.restofy.waiter.domain.exception.CallConflictException;
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
 * A waiter, help or bill call; Waiter is its only writer. Table, table session and guest session are logical
 * references to the QR service (no foreign keys). The transitions live here so that no other code can move a
 * call to an invalid state: OPEN → CLAIMED → RESOLVED, or OPEN → RESOLVED. A claim is optional.
 */
@Entity
@Table(name = "service_requests")
@Getter
public class ServiceRequest {

    @Id
    private UUID id;

    @Column(name = "restaurant_id", nullable = false)
    private UUID restaurantId;

    /** Null only for rows written before WAITER-02, which had no write path. */
    @Column(name = "table_id")
    private UUID tableId;

    @Column(name = "table_session_id", nullable = false)
    private UUID tableSessionId;

    @Column(name = "guest_session_id", nullable = false)
    private UUID guestSessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "request_type", nullable = false)
    private ServiceRequestType requestType;

    @Column(name = "message")
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ServiceRequestStatus status;

    @Column(name = "claimed_by_user_id")
    private UUID claimedByUserId;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "resolved_by_user_id")
    private UUID resolvedByUserId;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    /** Set only when the call ended unresolved because its table session closed; such a call is CANCELLED. */
    @Enumerated(EnumType.STRING)
    @Column(name = "closure_reason")
    private CallClosureReason closureReason;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    /** The table command (a QR-confirmed close) that ended this call. */
    @Column(name = "cancelled_by_command_id")
    private UUID cancelledByCommandId;

    /** Wrapper type on purpose: null marks a new entity, so Spring Data persists instead of merging. */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ServiceRequest() {
    }

    public static ServiceRequest open(UUID id, UUID restaurantId, UUID tableId, UUID tableSessionId,
                                      UUID guestSessionId, ServiceRequestType type, String message, Instant now) {
        ServiceRequest call = new ServiceRequest();
        call.id = id;
        call.restaurantId = restaurantId;
        call.tableId = tableId;
        call.tableSessionId = tableSessionId;
        call.guestSessionId = guestSessionId;
        call.requestType = type;
        call.message = message;
        call.status = ServiceRequestStatus.OPEN;
        call.createdAt = now;
        call.updatedAt = now;
        return call;
    }

    /** Claiming is only possible while nobody has claimed or resolved the call; it never overwrites a claimer. */
    public void claim(UUID staffUserId, Instant now) {
        if (status == ServiceRequestStatus.CLAIMED) {
            throw new CallConflictException("CALL_ALREADY_CLAIMED", "The call has already been claimed");
        }
        requireUnresolved();
        status = ServiceRequestStatus.CLAIMED;
        claimedByUserId = staffUserId;
        claimedAt = now;
        updatedAt = now;
    }

    /** Resolving works from OPEN and from CLAIMED, by any waiter of the restaurant; the resolver is recorded. */
    public void resolve(UUID staffUserId, Instant now) {
        requireUnresolved();
        status = ServiceRequestStatus.RESOLVED;
        resolvedByUserId = staffUserId;
        resolvedAt = now;
        updatedAt = now;
    }

    /**
     * Ends an unresolved call because QR confirmed that its main table session is closed. This is not a
     * resolution: nobody did the work, so the resolver fields stay empty and the call keeps its history
     * (claimer, creation time) instead of being deleted or marked as done.
     */
    public void cancelForTableClosure(UUID closingCommandId, Instant now) {
        requireUnresolved();
        status = ServiceRequestStatus.CANCELLED;
        closureReason = CallClosureReason.TABLE_SESSION_CLOSED;
        cancelledAt = now;
        cancelledByCommandId = closingCommandId;
        updatedAt = now;
    }

    private void requireUnresolved() {
        if (status == ServiceRequestStatus.RESOLVED) {
            throw new CallConflictException("CALL_ALREADY_RESOLVED", "The call has already been resolved");
        }
        if (status == ServiceRequestStatus.CANCELLED) {
            throw new CallConflictException("INVALID_TRANSITION", "A cancelled call cannot change state");
        }
    }
}
