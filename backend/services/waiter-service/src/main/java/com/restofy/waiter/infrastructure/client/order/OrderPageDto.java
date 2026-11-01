package com.restofy.waiter.infrastructure.client.order;

import java.util.List;

import static com.restofy.waiter.infrastructure.client.Fields.nonNegative;
import static com.restofy.waiter.infrastructure.client.Fields.required;
import static com.restofy.waiter.infrastructure.client.Fields.requiredList;

/**
 * DRAFT Order contract v0: one page of a search whose filters and ordering Order applies to the whole set.
 * The total is a wrapper so a missing total cannot silently mean "no orders".
 */
public record OrderPageDto(List<OrderDto> items, int page, int size, Long totalElements) {

    public OrderPageDto {
        items = requiredList(items, "items");
        nonNegative(page, "page");
        nonNegative(required(totalElements, "totalElements"), "totalElements");
        if (size <= 0) throw new IllegalArgumentException("size must be positive");
    }
}
