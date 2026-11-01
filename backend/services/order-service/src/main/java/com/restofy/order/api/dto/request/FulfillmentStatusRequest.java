package com.restofy.order.api.dto.request;

import com.restofy.order.domain.InputLimits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Moves an order to its next fulfillment status. The caller's identity and restaurant never come from the body. */
public record FulfillmentStatusRequest(
        @NotBlank @Size(max = InputLimits.MAX_STATUS_NAME_LENGTH) String status,
        @Size(max = InputLimits.MAX_TEXT_LENGTH) String reason) {
}
