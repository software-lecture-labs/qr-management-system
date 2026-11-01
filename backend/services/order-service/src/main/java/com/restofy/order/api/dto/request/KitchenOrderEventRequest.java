package com.restofy.order.api.dto.request;

import com.restofy.order.domain.InputLimits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/**
 * A preparation status the Kitchen Service reports for one order (Kitchen V1: WAITING, PREPARING, READY). {@code
 * eventId} is the Kitchen's own event id; redelivering the same id never changes anything twice.
 */
public record KitchenOrderEventRequest(@NotNull UUID eventId, @NotNull UUID restaurantId, @NotNull UUID orderId,
                                       @NotBlank @Size(max = InputLimits.MAX_STATUS_NAME_LENGTH) String kitchenStatus, Instant occurredAt) {
}
