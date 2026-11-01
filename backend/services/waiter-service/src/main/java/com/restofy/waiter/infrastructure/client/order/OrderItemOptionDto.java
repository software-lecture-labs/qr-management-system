package com.restofy.waiter.infrastructure.client.order;

import com.restofy.waiter.domain.enums.OptionAction;

import java.math.BigDecimal;

import static com.restofy.waiter.infrastructure.client.Fields.required;
import static com.restofy.waiter.infrastructure.client.Fields.requiredText;

/** DRAFT Order contract v0: snapshot of one ordered option. */
public record OrderItemOptionDto(String name, OptionAction action, int quantity, BigDecimal unitPriceDelta) {

    public OrderItemOptionDto {
        requiredText(name, "option.name");
        required(action, "option.action");
        required(unitPriceDelta, "option.unitPriceDelta");
        if (quantity <= 0) throw new IllegalArgumentException("option.quantity must be positive");
    }
}
