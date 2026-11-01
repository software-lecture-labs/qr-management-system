package com.restofy.waiter.infrastructure.client.order;

import com.restofy.waiter.domain.enums.CancellationSource;
import com.restofy.waiter.domain.enums.CancellationStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.restofy.waiter.infrastructure.client.Fields.required;
import static com.restofy.waiter.infrastructure.client.Fields.requiredList;

/** DRAFT Order contract v0: cancellation request and its decision. Only Order decides a cancellation. */
public record CancellationDto(UUID id, CancellationSource source, CancellationStatus status, String reasonCode,
                              String reasonText, Instant requestedAt, Instant decidedAt,
                              List<CancellationItemDto> items) {

    public CancellationDto {
        required(id, "cancellation.id");
        required(source, "cancellation.source");
        required(status, "cancellation.status");
        required(requestedAt, "cancellation.requestedAt");
        items = requiredList(items, "cancellation.items");
    }
}
