package com.restofy.waiter.application.service;

import org.springframework.data.domain.Sort;

/**
 * Allow-listed call orderings. Clients choose a name, never a column, so the sort can neither reach an
 * unindexed column nor inject anything. The id is always the tie breaker so paging is stable.
 */
public enum CallSort {
    /** Oldest call first: the call that has waited longest is on top (default). */
    CREATED_ASC(Sort.Direction.ASC),
    CREATED_DESC(Sort.Direction.DESC);

    private final Sort.Direction direction;

    CallSort(Sort.Direction direction) {
        this.direction = direction;
    }

    public Sort toSort() {
        return Sort.by(new Sort.Order(direction, "createdAt"), new Sort.Order(Sort.Direction.ASC, "id"));
    }
}
