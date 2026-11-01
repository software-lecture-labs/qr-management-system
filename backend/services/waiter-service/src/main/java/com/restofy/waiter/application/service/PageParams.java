package com.restofy.waiter.application.service;

import com.restofy.waiter.domain.exception.InvalidRequestException;

/** Validated paging input. The upper bound keeps one request from pulling an unbounded result. */
public record PageParams(int page, int size) {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    public PageParams {
        if (page < 0) throw new InvalidRequestException("page must be 0 or greater");
        if (size < 1 || size > MAX_SIZE) throw new InvalidRequestException("size must be between 1 and " + MAX_SIZE);
    }

    public long offset() {
        return (long) page * size;
    }
}
