package com.restofy.waiter.infrastructure.client.order;

import com.restofy.waiter.domain.enums.DeliveryStatus;

import java.util.UUID;

import static com.restofy.waiter.infrastructure.client.Fields.required;
import static com.restofy.waiter.infrastructure.client.Fields.requiredText;

/**
 * DRAFT Order contract v0: what Order recorded for a command id. {@code APPLIED} means this very command made
 * the order served; {@code REJECTED} means Order refused it and says why. A command Order never recorded is
 * answered with 404 and is not represented here.
 */
public record ServeStatusDto(UUID commandId, Status status, String rejectionCode, OrderDto order) {

    public enum Status { APPLIED, REJECTED }

    public ServeStatusDto {
        required(commandId, "commandId");
        required(status, "status");
        if (status == Status.APPLIED) {
            required(order, "order");
            if (order.deliveryStatus() != DeliveryStatus.SERVED) {
                throw new IllegalArgumentException("an applied serve command must belong to a served order");
            }
        } else {
            requiredText(rejectionCode, "rejectionCode");
        }
    }
}
