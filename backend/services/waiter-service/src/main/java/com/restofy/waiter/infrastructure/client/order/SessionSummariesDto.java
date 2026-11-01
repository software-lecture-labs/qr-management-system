package com.restofy.waiter.infrastructure.client.order;

import java.util.List;
import java.util.UUID;

import static com.restofy.waiter.infrastructure.client.Fields.required;
import static com.restofy.waiter.infrastructure.client.Fields.requiredList;

/** DRAFT Order contract v0: active order count per requested session. Sessions without orders may be omitted. */
public record SessionSummariesDto(List<Entry> items) {

    public SessionSummariesDto {
        items = requiredList(items, "items");
    }

    /** The count is a wrapper on purpose: a missing count must fail instead of silently meaning "no orders". */
    public record Entry(UUID tableSessionId, Integer activeOrderCount) {

        public Entry {
            required(tableSessionId, "summary.tableSessionId");
            if (required(activeOrderCount, "summary.activeOrderCount") < 0) {
                throw new IllegalArgumentException("summary.activeOrderCount must not be negative");
            }
        }
    }
}
