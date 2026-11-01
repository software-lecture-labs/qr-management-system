package com.restofy.waiter.api.dto.response;

import java.util.List;

/** Page envelope: totals always describe the whole filtered data set, never just the returned slice. */
public record PageResponse<T>(List<T> items, int page, int size, long totalElements, int totalPages) {

    public static <T> PageResponse<T> of(List<T> items, int page, int size, long totalElements) {
        int totalPages = (int) Math.min(Integer.MAX_VALUE, (totalElements + size - 1) / size);
        return new PageResponse<>(List.copyOf(items), page, size, totalElements, totalPages);
    }
}
