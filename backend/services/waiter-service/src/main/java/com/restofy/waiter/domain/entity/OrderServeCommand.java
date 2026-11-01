package com.restofy.waiter.domain.entity;

import com.restofy.waiter.domain.enums.ServeCommandStatus;
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
 * Technical record of one "mark as served" command sent to Order. It is not the delivery fact: Order owns
 * that. The record exists so that a command whose answer was lost can be found again by its id and reconciled.
 * The id doubles as the idempotency key towards Order, so every resend is recognisably the same command.
 */
@Entity
@Table(name = "order_serve_commands")
@Getter
public class OrderServeCommand {

    @Id
    private UUID id;

    @Column(name = "restaurant_id", nullable = false)
    private UUID restaurantId;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "table_session_id", nullable = false)
    private UUID tableSessionId;

    @Column(name = "expected_version", nullable = false)
    private long expectedVersion;

    @Column(name = "requested_by_user_id", nullable = false)
    private UUID requestedByUserId;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "request_digest", nullable = false, length = 64)
    private String requestDigest;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ServeCommandStatus status;

    @Column(name = "rejection_code")
    private String rejectionCode;

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

    protected OrderServeCommand() {
    }

    public static OrderServeCommand open(UUID id, UUID restaurantId, UUID orderId, UUID tableSessionId,
                                         long expectedVersion, UUID requestedByUserId, String idempotencyKey,
                                         String requestDigest, Instant now) {
        OrderServeCommand command = new OrderServeCommand();
        command.id = id;
        command.restaurantId = restaurantId;
        command.orderId = orderId;
        command.tableSessionId = tableSessionId;
        command.expectedVersion = expectedVersion;
        command.requestedByUserId = requestedByUserId;
        command.idempotencyKey = idempotencyKey;
        command.requestDigest = requestDigest;
        command.status = ServeCommandStatus.PENDING;
        command.createdAt = now;
        command.updatedAt = now;
        return command;
    }

    /** Marks a new attempt in flight; the version column makes two concurrent attempts collide. */
    public void markInFlight(Instant now) {
        requireUnanswered();
        status = ServeCommandStatus.PENDING;
        updatedAt = now;
    }

    public void markUnknown(Instant now) {
        requireUnanswered();
        status = ServeCommandStatus.UNKNOWN;
        updatedAt = now;
    }

    public void confirm(Instant now) {
        requireUnanswered();
        status = ServeCommandStatus.CONFIRMED;
        updatedAt = now;
        completedAt = now;
    }

    public void reject(String code, Instant now) {
        requireUnanswered();
        status = ServeCommandStatus.REJECTED;
        rejectionCode = code;
        updatedAt = now;
        completedAt = now;
    }

    /** True while no definite answer of Order exists. */
    public boolean isUnanswered() {
        return status == ServeCommandStatus.PENDING || status == ServeCommandStatus.UNKNOWN;
    }

    /** An answer of Order is final: a command can never turn from confirmed to rejected or back. */
    private void requireUnanswered() {
        if (!isUnanswered()) throw new IllegalStateException("The serve command already has Order's answer");
    }
}
