package com.restofy.waiter.application.service;

import com.restofy.waiter.domain.enums.ServiceRequestStatus;
import com.restofy.waiter.domain.enums.ServiceRequestType;
import com.restofy.waiter.domain.exception.InvalidRequestException;

import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * Validated listing criteria. Empty type or status sets mean "no filter". The creation window is half-open,
 * {@code [createdFrom, createdTo)}, so adjacent windows neither overlap nor leave a gap.
 */
public record CallFilter(Set<ServiceRequestType> types, Set<ServiceRequestStatus> statuses, UUID tableId,
                         UUID tableSessionId, Instant createdFrom, Instant createdTo, CallSort sort) {

    /** More repeated values than there are enum constants is a malformed request, not a wider filter. */
    private static final int MAX_VALUES = 10;

    public CallFilter {
        types = copy(types, "type");
        statuses = copy(statuses, "status");
        sort = sort == null ? CallSort.CREATED_ASC : sort;
        if (createdFrom != null && createdTo != null && !createdFrom.isBefore(createdTo)) {
            throw new InvalidRequestException("createdFrom must be earlier than createdTo");
        }
    }

    public static CallFilter of(Collection<ServiceRequestType> types, Collection<ServiceRequestStatus> statuses,
                                UUID tableId, UUID tableSessionId, Instant createdFrom, Instant createdTo,
                                CallSort sort) {
        return new CallFilter(types == null ? null : checked(types, "type"),
                statuses == null ? null : checked(statuses, "status"),
                tableId, tableSessionId, createdFrom, createdTo, sort);
    }

    private static <E extends Enum<E>> Set<E> checked(Collection<E> values, String name) {
        if (values.size() > MAX_VALUES) throw new InvalidRequestException("Too many '" + name + "' values");
        return values.isEmpty() ? Set.of() : EnumSet.copyOf(values);
    }

    private static <E extends Enum<E>> Set<E> copy(Set<E> values, String name) {
        if (values == null || values.isEmpty()) return Set.of();
        if (values.size() > MAX_VALUES) throw new InvalidRequestException("Too many '" + name + "' values");
        return Set.copyOf(EnumSet.copyOf(values));
    }
}
