package com.restofy.waiter.api.dto.request;

import com.restofy.waiter.domain.enums.ServiceRequestType;
import com.restofy.waiter.domain.exception.InvalidRequestException;
import jakarta.validation.constraints.NotNull;

/**
 * A guest's call. Only the type and an optional free text are accepted: restaurant, table and sessions come
 * from QR's verification of the guest credential, and unknown fields are rejected rather than ignored.
 *
 * <p>The text is read as a generic value on purpose: JSON would otherwise silently turn a number or boolean
 * into text, and a message must be an actual string. Use {@link #text()} to get it.
 */
public record CreateCallRequest(@NotNull ServiceRequestType type, Object message) {

    public CreateCallRequest {
        if (message != null && !(message instanceof String)) {
            throw new InvalidRequestException("message must be a string");
        }
    }

    public String text() {
        return (String) message;
    }
}
