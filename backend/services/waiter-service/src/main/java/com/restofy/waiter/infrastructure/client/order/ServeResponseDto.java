package com.restofy.waiter.infrastructure.client.order;

import com.restofy.waiter.domain.enums.DeliveryStatus;

import java.util.UUID;

import static com.restofy.waiter.infrastructure.client.Fields.required;

/**
 * DRAFT Order contract v0: Order applied the serve command ({@code APPLIED}) or had applied the very same
 * command id before ({@code DUPLICATE}). Either way the returned order must be served, otherwise the answer
 * contradicts itself and is treated as unusable (the outcome then stays unknown).
 */
public record ServeResponseDto(UUID commandId, Outcome outcome, OrderDto order) {

    public enum Outcome { APPLIED, DUPLICATE }

    public ServeResponseDto {
        required(commandId, "commandId");
        required(outcome, "outcome");
        required(order, "order");
        if (order.deliveryStatus() != DeliveryStatus.SERVED) {
            throw new IllegalArgumentException("an applied serve command must return a served order");
        }
    }
}
