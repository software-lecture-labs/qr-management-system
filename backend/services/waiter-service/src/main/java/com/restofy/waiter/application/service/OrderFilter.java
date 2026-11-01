package com.restofy.waiter.application.service;

import com.restofy.waiter.domain.enums.OrderSessionScope;
import com.restofy.waiter.domain.enums.OrderView;

import java.util.UUID;

/** Allow-listed order filters. {@code scope} defaults to the live sessions so history never mixes into them. */
public record OrderFilter(OrderView view, UUID tableId, OrderSessionScope scope) {

    public OrderFilter {
        scope = scope == null ? OrderSessionScope.CURRENT : scope;
    }
}
