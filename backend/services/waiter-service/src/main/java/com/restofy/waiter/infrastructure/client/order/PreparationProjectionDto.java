package com.restofy.waiter.infrastructure.client.order;

import com.restofy.waiter.domain.enums.PreparationStatus;

import java.time.Instant;

import static com.restofy.waiter.infrastructure.client.Fields.nonNegative;
import static com.restofy.waiter.infrastructure.client.Fields.required;

/**
 * DRAFT Order contract v0: versioned projection of the Kitchen preparation state kept by Order from the
 * KitchenOrderReady/KitchenPreparationStarted events. Kitchen stays the authority; {@code asOf} tells how
 * fresh the projection is.
 */
public record PreparationProjectionDto(PreparationStatus status, Instant startedAt, Instant readyAt,
                                       long sourceVersion, Instant asOf) {

    public PreparationProjectionDto {
        required(status, "preparation.status");
        required(asOf, "preparation.asOf");
        nonNegative(sourceVersion, "preparation.sourceVersion");
        if (status == PreparationStatus.READY && readyAt == null) {
            throw new IllegalArgumentException("preparation.readyAt is required when the status is READY");
        }
    }
}
