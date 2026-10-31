package com.restofy.waiter.infrastructure.client.order;

import com.restofy.waiter.domain.enums.DeliveryStatus;
import com.restofy.waiter.domain.enums.OrderBusinessStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.restofy.waiter.infrastructure.client.Fields.nonNegative;
import static com.restofy.waiter.infrastructure.client.Fields.required;
import static com.restofy.waiter.infrastructure.client.Fields.requiredList;
import static com.restofy.waiter.infrastructure.client.Fields.requiredText;

/**
 * DRAFT Order contract v0: one order with its snapshot lines. Order owns the business and delivery axes;
 * {@code preparation} and {@code payment} are projections and may be absent (for example when the Kitchen
 * record has not reached Order yet), which is not the same as "not ready". Waiter assesses no closure from these
 * figures: whether a table session may close is Order's assessment for QR (ADR-QR-001 K7).
 */
public record OrderDto(UUID id, UUID restaurantId, String orderNumber, UUID tableId, UUID tableSessionId,
                       OrderBusinessStatus businessStatus, DeliveryStatus deliveryStatus,
                       Instant acceptedAt, Instant servedAt,
                       PreparationProjectionDto preparation, PaymentProjectionDto payment,
                       String currency, BigDecimal originalGrossTotal, BigDecimal cancelledGrossTotal,
                       BigDecimal payableTotal, List<OrderItemDto> items, List<CancellationDto> cancellations,
                       long version) {

    public OrderDto {
        required(id, "order.id");
        required(restaurantId, "order.restaurantId");
        requiredText(orderNumber, "order.orderNumber");
        required(tableId, "order.tableId");
        required(tableSessionId, "order.tableSessionId");
        required(businessStatus, "order.businessStatus");
        required(deliveryStatus, "order.deliveryStatus");
        required(acceptedAt, "order.acceptedAt");
        requiredText(currency, "order.currency");
        required(originalGrossTotal, "order.originalGrossTotal");
        required(cancelledGrossTotal, "order.cancelledGrossTotal");
        required(payableTotal, "order.payableTotal");
        items = requiredList(items, "order.items");
        cancellations = requiredList(cancellations, "order.cancellations");
        nonNegative(version, "order.version");
        if (items.isEmpty()) throw new IllegalArgumentException("order.items must not be empty");
        if (deliveryStatus == DeliveryStatus.SERVED && servedAt == null) {
            throw new IllegalArgumentException("order.servedAt is required when the delivery status is SERVED");
        }
        if (businessStatus == OrderBusinessStatus.CANCELLED && deliveryStatus == DeliveryStatus.SERVED) {
            throw new IllegalArgumentException("a cancelled order cannot be served");
        }
    }
}
