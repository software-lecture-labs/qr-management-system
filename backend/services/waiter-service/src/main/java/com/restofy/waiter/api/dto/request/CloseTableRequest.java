package com.restofy.waiter.api.dto.request;

import com.restofy.waiter.domain.exception.InvalidRequestException;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * "Close this main table session", with the context the staff member saw: the session version. The session is
 * named by the path, the restaurant and the staff member come from the verified identity. The optional
 * {@code reason} is free text for the audit trail. There is deliberately nothing that could claim payment,
 * balance or an exception ({@code paid}, {@code remainingAmount}, {@code force} ...): unknown fields are
 * rejected, and the owners of the facts verify them when QR closes the session.
 *
 * <p>The reason is read as a generic value on purpose, so a number or boolean is not silently turned into text.
 */
public record CloseTableRequest(@NotNull @PositiveOrZero Long expectedVersion, Object reason) {

    public CloseTableRequest {
        if (reason != null && !(reason instanceof String)) {
            throw new InvalidRequestException("reason must be a string");
        }
    }

    public String reasonText() {
        return (String) reason;
    }
}
