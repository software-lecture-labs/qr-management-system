package com.restofy.waiter.api.dto.response;

import com.restofy.waiter.domain.enums.DeliveryStatus;
import com.restofy.waiter.domain.enums.OrderBusinessStatus;
import com.restofy.waiter.domain.enums.PreparationStatus;
import com.restofy.waiter.domain.enums.ServeCommandStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * The technical command record plus, when Order has answered, Order's own view of the order. The delivery fact
 * lives in {@code order} (copied from Order's answer); the command {@code status} only says how far the command
 * got: {@code CONFIRMED} and {@code REJECTED} are answers of Order, {@code PENDING} and {@code UNKNOWN} mean the
 * order may or may not have been served. {@code order} is null when Order has not answered (or could not be asked).
 * {@code reconciliation} is {@code NOT_RECORDED} when Order was asked and has no record of the command.
 */
public record ServeCommandResponse(UUID commandId, UUID orderId, UUID tableSessionId, long expectedVersion,
                                   ServeCommandStatus status, String rejectionCode, Instant requestedAt,
                                   Instant completedAt, String reconciliation, ServedOrder order) {

    /** Order's answer, reduced to what the panel needs after serving. Kitchen's READY is carried unchanged. */
    public record ServedOrder(UUID id, String orderNumber, UUID tableSessionId, OrderBusinessStatus businessStatus,
                              DeliveryStatus deliveryStatus, Instant servedAt, PreparationStatus preparationStatus,
                              long version) {
    }
}
