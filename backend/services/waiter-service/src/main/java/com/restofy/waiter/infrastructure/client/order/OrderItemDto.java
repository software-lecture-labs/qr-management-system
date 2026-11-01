package com.restofy.waiter.infrastructure.client.order;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static com.restofy.waiter.infrastructure.client.Fields.required;
import static com.restofy.waiter.infrastructure.client.Fields.requiredList;
import static com.restofy.waiter.infrastructure.client.Fields.requiredText;

/** DRAFT Order contract v0: snapshot of one order line. Prices are the snapshot taken when the order was accepted. */
public record OrderItemDto(UUID id, UUID productId, String productName, int quantity, int cancelledQuantity,
                           BigDecimal unitGrossPrice, BigDecimal lineGrossTotal, String note,
                           List<OrderItemOptionDto> options) {

    public OrderItemDto {
        required(id, "item.id");
        required(productId, "item.productId");
        requiredText(productName, "item.productName");
        required(unitGrossPrice, "item.unitGrossPrice");
        required(lineGrossTotal, "item.lineGrossTotal");
        options = requiredList(options, "item.options");
        if (quantity <= 0) throw new IllegalArgumentException("item.quantity must be positive");
        if (cancelledQuantity < 0 || cancelledQuantity > quantity) {
            throw new IllegalArgumentException("item.cancelledQuantity must be between 0 and quantity");
        }
    }
}
